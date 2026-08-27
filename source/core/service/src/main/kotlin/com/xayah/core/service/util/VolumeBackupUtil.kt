package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.util.parseVolumePartFileName
import com.xayah.core.model.util.volumePartFileName
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Splits a compressed backup stream into fixed-size volume parts and, when
 * streaming is enabled, uploads each part as soon as it is finalized. The
 * compression runs in the root shell and `busybox split` writes the volume
 * files directly, so the archive bytes never cross into the app process.
 */
class VolumeBackupUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "VolumeBackupUtil"
        private const val VOLUME_SUFFIX_LEN = 5
        private const val POLL_INTERVAL_MS = 300L
    }

    data class VolumePart(val localPath: String, val index: Int)

    private fun log(msg: () -> String): String = run {
        val text = msg()
        LogUtil.log { TAG to text }
        text
    }

    private fun splitCommand(command: String, dstDir: String, baseName: String, suffix: String, volumeSize: Long): String {
        val prefix = "$dstDir/$baseName.$suffix.part"
        return "$command | busybox split -b $volumeSize -d -a $VOLUME_SUFFIX_LEN - ${SymbolUtil.QUOTE}$prefix${SymbolUtil.QUOTE}"
    }

    private suspend fun listVolumeParts(dstDir: String, baseName: String, suffix: String): List<VolumePart> =
        rootService.listFilePaths(dstDir)
            .mapNotNull { path ->
                parseVolumePartFileName(PathUtil.getFileName(path))?.let { info ->
                    if (info.baseName == baseName && info.suffix == suffix) {
                        VolumePart(localPath = path, index = info.index)
                    } else {
                        null
                    }
                }
            }
            .sortedBy { it.index }

    /**
     * Runs [command] (tar ... | zstd ...) and splits its output into volume
     * parts of [volumeSize] bytes under [dstDir], then uploads them to
     * [remoteDstDir].
     *
     * @param stream when true, upload runs concurrently with compression. The
     *               producer is only allowed to stay a bounded amount ahead:
     *               already-finalized volumes are uploaded and deleted, so disk
     *               usage stays close to a couple of volumes.
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

        val uploadPart: suspend (VolumePart) -> Boolean = { part ->
            val size = File(part.localPath).length()
            val result = cloudRepository.upload(client = client, src = part.localPath, dstDir = remoteDstDir)
            if (result.isSuccess) {
                uploadedBytes.addAndGet(size)
                onUploading(uploadedBytes.get(), 0)
            }
            result.isSuccess
        }

        // Ensure the app process can read/write files inside filesDir.
        PathUtil.setFilesDirSELinux(context)
        rootService.mkdirs(dstDir)

        val fullCommand = splitCommand(command, dstDir, baseName, suffix, volumeSize)

        if (stream) {
            val producer = async(Dispatchers.IO) { BaseUtil.execute(fullCommand).code }
            var producerDone = false
            var nextToUpload = 0

            while (true) {
                if (!producerDone && producer.isCompleted) {
                    producerDone = true
                    producerCode = producer.await()
                }

                val parts = listVolumeParts(dstDir, baseName, suffix)
                val count = parts.size
                // While the producer is still running, the highest-numbered part
                // may be incomplete, so it is not uploaded yet.
                val safeCount = if (producerDone) count else (count - 1).coerceAtLeast(0)

                while (nextToUpload < safeCount) {
                    val part = parts.getOrNull(nextToUpload) ?: break
                    if (uploadPart(part)) {
                        nextToUpload++
                    } else {
                        failures.incrementAndGet()
                        out.add(log { "Failed to upload: ${part.localPath}" })
                        nextToUpload++
                    }
                }

                if (producerDone && nextToUpload >= count) break
                delay(POLL_INTERVAL_MS)
            }
        } else {
            producerCode = withContext(Dispatchers.IO) { BaseUtil.execute(fullCommand).code }
            listVolumeParts(dstDir, baseName, suffix).forEach { part ->
                if (!uploadPart(part)) {
                    failures.incrementAndGet()
                    out.add(log { "Failed to upload: ${part.localPath}" })
                }
            }
        }

        ShellResult(
            code = if (producerCode == 0 && failures.get() == 0) 0 else -1,
            input = listOf(fullCommand),
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
}
