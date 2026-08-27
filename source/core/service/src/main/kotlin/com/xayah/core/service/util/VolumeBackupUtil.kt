package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.util.parseVolumePartFileName
import com.xayah.core.model.util.volumePartFileName
import com.xayah.core.model.util.volumePartPath
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Splits a compressed backup stream into fixed-size volume parts and, when
 * streaming is enabled, uploads each part as soon as it is finalized. The
 * uploader deletes every uploaded part locally, so disk usage stays bounded by
 * roughly (STREAM_QUEUE_CAPACITY * volumeSize).
 */
class VolumeBackupUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "VolumeBackupUtil"
        private const val STREAM_QUEUE_CAPACITY = 2
        private val END_MARKER = Any()
    }

    data class VolumePart(val localPath: String, val index: Int)

    private fun log(msg: () -> String): String = run {
        val text = msg()
        LogUtil.log { TAG to text }
        text
    }

    /**
     * Runs [command] (which must emit a compressed tar stream on stdout),
     * splits it into volume parts of [volumeSize] bytes under [dstDir] and
     * uploads them to [remoteDstDir].
     *
     * @param stream when true, upload runs concurrently with compression and
     *               compression blocks while the bounded upload queue is full.
     */
    suspend fun compressAndUpload(
        client: CloudClient,
        command: String,
        dstDir: String,
        remoteDstDir: String,
        baseName: String,
        suffix: String,
        volumeSize: Long,
        stream: Boolean,
        onUploading: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): ShellResult = coroutineScope {
        val out = mutableListOf<String>()
        val failures = AtomicInteger(0)
        val uploadedBytes = AtomicLong(0)
        var producerCode = -1

        // Ensure the app process can read/write files inside filesDir.
        PathUtil.setFilesDirSELinux(context)
        rootService.mkdirs(dstDir)

        if (stream) {
            val queue = ArrayBlockingQueue<Any>(STREAM_QUEUE_CAPACITY)

            val consumer = launch(Dispatchers.IO) {
                while (true) {
                    val item = queue.take()
                    if (item === END_MARKER) break
                    item as VolumePart
                    val size = File(item.localPath).length()
                    val result = cloudRepository.upload(client = client, src = item.localPath, dstDir = remoteDstDir)
                    if (result.isSuccess) {
                        uploadedBytes.addAndGet(size)
                        onUploading(uploadedBytes.get(), 0)
                    } else {
                        failures.incrementAndGet()
                        out.add(log { "Failed to upload: ${item.localPath}" })
                    }
                }
            }

            producerCode = withContext(Dispatchers.IO) {
                val volumeStream = VolumeOutputStream(dstDir, baseName, suffix, volumeSize) { part -> queue.put(part) }
                try {
                    BaseUtil.executeToStream(command, volumeStream).code
                } finally {
                    volumeStream.close()
                    queue.put(END_MARKER)
                }
            }

            consumer.join()
        } else {
            val parts = mutableListOf<VolumePart>()
            producerCode = withContext(Dispatchers.IO) {
                val volumeStream = VolumeOutputStream(dstDir, baseName, suffix, volumeSize) { part -> parts.add(part) }
                try {
                    BaseUtil.executeToStream(command, volumeStream).code
                } finally {
                    volumeStream.close()
                }
            }

            parts.forEach { part ->
                val size = File(part.localPath).length()
                val result = cloudRepository.upload(client = client, src = part.localPath, dstDir = remoteDstDir)
                if (result.isSuccess) {
                    uploadedBytes.addAndGet(size)
                    onUploading(uploadedBytes.get(), 0)
                } else {
                    failures.incrementAndGet()
                    out.add(log { "Failed to upload: ${part.localPath}" })
                }
            }
        }

        ShellResult(
            code = if (producerCode == 0 && failures.get() == 0) 0 else -1,
            input = listOf(command),
            out = out,
        )
    }

    /**
     * Downloads all volume parts of an archive from [srcDir] and concatenates
     * them into the canonical single-file archive path under [dstDir].
     */
    suspend fun downloadAndMerge(
        client: CloudClient,
        srcDir: String,
        dstDir: String,
        baseName: String,
        suffix: String,
    ): ShellResult = coroutineScope {
        val out = mutableListOf<String>()
        var isSuccess = true

        val fileNames = runCatching { client.listFiles(srcDir).files.map { it.name } }.getOrElse {
            isSuccess = false
            out.add(log { "Failed to list $srcDir." })
            emptyList()
        }

        val parts = fileNames
            .mapNotNull { parseVolumePartFileName(it) }
            .filter { it.baseName == baseName && it.suffix == suffix }
            .sortedBy { it.index }

        if (parts.isEmpty()) {
            isSuccess = false
            out.add(log { "No volume part found for $baseName.$suffix in $srcDir." })
        } else {
            PathUtil.setFilesDirSELinux(context)
            val mergeDir = "$dstDir/.volume_merge"
            rootService.deleteRecursively(mergeDir)
            rootService.mkdirs(mergeDir)

            parts.forEach { part ->
                val remotePath = "$srcDir/${volumePartFileName(baseName, suffix, part.index)}"
                try {
                    withContext(Dispatchers.IO) {
                        client.download(src = remotePath, dst = mergeDir) { _, _ -> }
                    }
                } catch (t: Throwable) {
                    isSuccess = false
                    out.add(log { "Failed to download $remotePath." })
                }
            }

            if (isSuccess) {
                val canonical = "$dstDir/$baseName.$suffix"
                val catCommand = "cat $mergeDir/$baseName.$suffix.part* > ${SymbolUtil.QUOTE}$canonical${SymbolUtil.QUOTE}"
                BaseUtil.execute(catCommand).also { result ->
                    isSuccess = result.isSuccess
                    out.addAll(result.out)
                }
            }

            rootService.deleteRecursively(mergeDir)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    private class VolumeOutputStream(
        private val dstDir: String,
        private val baseName: String,
        private val suffix: String,
        private val volumeSize: Long,
        private val onPart: (VolumePart) -> Unit,
    ) : OutputStream() {
        private var current: FileOutputStream? = null
        private var index = 0
        private var currentBytes = 0L
        private var finished = false

        private fun ensureCurrent() {
            if (current == null) {
                current = FileOutputStream(volumePartPath(dstDir, baseName, suffix, index))
                currentBytes = 0L
            }
        }

        private fun rollOver() {
            val stream = current ?: return
            stream.flush()
            stream.close()
            current = null
            onPart(VolumePart(volumePartPath(dstDir, baseName, suffix, index), index))
            index++
            currentBytes = 0L
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var offset = off
            while (remaining > 0) {
                ensureCurrent()
                val space = volumeSize - currentBytes
                val chunk = minOf(remaining.toLong(), space).toInt()
                current!!.write(b, offset, chunk)
                currentBytes += chunk
                offset += chunk
                remaining -= chunk
                if (currentBytes >= volumeSize) {
                    rollOver()
                }
            }
        }

        override fun close() {
            if (finished) return
            if (current != null) {
                if (currentBytes > 0) {
                    rollOver()
                } else {
                    current!!.close()
                    current = null
                    index++
                }
            }
            finished = true
            super.close()
        }
    }
}
