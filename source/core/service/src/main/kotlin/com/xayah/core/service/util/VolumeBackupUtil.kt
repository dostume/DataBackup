package com.xayah.core.service.util

import android.content.Context
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Splits a compressed backup stream into fixed-size volume parts and, when
 * streaming is enabled, uploads each part as soon as it is finalized and
 * deletes the local copy right afterwards ("边上传边删除"). The compression runs
 * in the root shell and `busybox split` writes the volume files directly, so
 * the archive bytes never cross into the app process.
 */
class VolumeBackupUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "VolumeBackupUtil"
        private const val VOLUME_SUFFIX_LEN = 5
        private const val POLL_INTERVAL_MS = 300L
        private const val SPLITER_BINARY = "busybox"
    }

    data class VolumePart(val localPath: String, val index: Int)

    private fun log(msg: () -> String): String = run {
        val text = msg()
        LogUtil.log { TAG to text }
        text
    }

    private fun splitCommand(command: String, dstDir: String, baseName: String, suffix: String, volumeSize: Long): String {
        val prefix = "$dstDir/$baseName.$suffix.part"
        // umask 022: the volume files are created by the root shell, make sure
        // they are world-readable so the app process can upload them without a
        // recursive chown (which would need the busy root shell and serialize streaming).
        return "umask 022; $command | $SPLITER_BINARY split -b $volumeSize -d -a $VOLUME_SUFFIX_LEN - ${SymbolUtil.QUOTE}$prefix${SymbolUtil.QUOTE}"
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
     * Deletes remote archives of `$baseName.$suffix` under [remoteDstDir]:
     * the volume parts and, if [includeSingle], the single-file archive too.
     * Returns false only if the remote directory could not be listed.
     */
    private suspend fun deleteRemoteArchivesImpl(client: CloudClient, remoteDstDir: String, baseName: String, suffix: String, includeSingle: Boolean): Boolean {
        val names = runCatching {
            client.listFiles(remoteDstDir).files.map { it.name }
        }.getOrElse {
            log { "Failed to list $remoteDstDir: ${it.localizedMessage}" }
            return false
        }

        val single = "$baseName.$suffix"
        names.forEach { name ->
            val isPart = parseVolumePartFileName(name)?.let { it.baseName == baseName && it.suffix == suffix } == true
            if ((includeSingle && name == single) || isPart) {
                runCatching { withContext(Dispatchers.IO) { client.deleteFile("$remoteDstDir/$name") } }
                    .onSuccess { log { "Deleted stale remote archive: $name" } }
                    .onFailure { log { "Failed to delete stale remote archive: $name: ${it.localizedMessage}" } }
            }
        }
        return true
    }

    /**
     * Removes stale remote archives (single file and volume parts) of the
     * given archive, called before a new volume backup is uploaded. Leftovers
     * of a previous backup (e.g. a different part count, or a single-file
     * archive from before volume mode was enabled) would be merged with the
     * new parts on restore and silently corrupt the archive.
     */
    suspend fun deleteRemoteArchives(client: CloudClient, remoteDstDir: String, baseName: String, suffix: String): Boolean =
        deleteRemoteArchivesImpl(client, remoteDstDir, baseName, suffix, includeSingle = true)

    /**
     * Removes stale remote volume parts (only) of the given archive, called
     * before a new single-file archive is uploaded, so that a restore never
     * picks up the old volume parts of a previous volume-mode backup.
     */
    suspend fun deleteRemoteVolumeParts(client: CloudClient, remoteDstDir: String, baseName: String, suffix: String): Boolean =
        deleteRemoteArchivesImpl(client, remoteDstDir, baseName, suffix, includeSingle = false)

    /**
     * Runs [command] (tar ... | zstd ...) and splits its output into volume
     * parts of [volumeSize] bytes under [dstDir], then uploads them to
     * [remoteDstDir].
     *
     * @param stream when true, upload runs concurrently with compression. Each
     *               finalized volume is uploaded and its local copy deleted, so
     *               disk usage stays close to a couple of volumes.
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
        val uploadedBytes = AtomicLong(0)
        val uploadedIndexes = mutableListOf<Int>()
        var remoteCleaned = false
        var aborted = false
        var producerCode = -1

        // volumeSize > 0 means volume (split) mode. The app bundles busybox, tar, zstd in assets/bin.zip,
        // released to context.binDir() by BaseUtil.releaseBase(). The root shell's PATH includes binDir(),
        // so "busybox split" works directly. Check that the bundled busybox is available; if not, try to
        // release it automatically. Many OEM ROMs (OPPO/OnePlus ColorOS etc.) do not ship system busybox.
        if (volumeSize > 0) {
            val busyboxPath = "${context.binDir()}/$SPLITER_BINARY"
            var hasSpliter = runCatching { rootService.exists(busyboxPath) }.getOrDefault(false)
            if (hasSpliter.not()) {
                out.add(log { "Built-in busybox not found at $busyboxPath, attempting to release from assets..." })
                hasSpliter = runCatching { BaseUtil.releaseBase(context) }.getOrDefault(false)
                if (hasSpliter) {
                    hasSpliter = runCatching { rootService.exists(busyboxPath) }.getOrDefault(false)
                }
            }
            if (hasSpliter.not()) {
                out.add(log { "Split tool unavailable: $busyboxPath does not exist after release attempt. " +
                    "Please ensure the app's built-in binaries are present, or install a BusyBox app and grant root." })
                return@coroutineScope ShellResult(code = -1, input = listOf(), out = out)
            }
            out.add(log { "BusyBox split tool available at $busyboxPath" })
        }

        rootService.mkdirs(dstDir)
        // Ensure the app process can read files inside filesDir (recursive
        // chown/chcon). Called once here: calling it per upload would queue behind the busy root shell and break streaming ("边上传边删除").
        PathUtil.setFilesDirSELinux(context)

        // Remove stale local parts from a previous failed run so they can not
        // be mixed into this archive.
        listVolumeParts(dstDir, baseName, suffix).forEach { part ->
            rootService.deleteRecursively(part.localPath)
            out.add(log { "Deleted stale local part: ${part.localPath}" })
        }

        // volumeSize == 0: single-file mode. Write the compressed archive
        // directly to a single file instead of splitting into volumes.
        val singleFilePath = "$dstDir/$baseName.$suffix"
        val useSingleFile = volumeSize <= 0L
        val fullCommand = if (useSingleFile) {
            "umask 022; $command > ${SymbolUtil.QUOTE}$singleFilePath${SymbolUtil.QUOTE}"
        } else {
            splitCommand(command, dstDir, baseName, suffix, volumeSize)
        }

        // Uploads one part. On success the local copy is deleted right away.
        // On failure the local copy is kept and the whole run aborts: a missing
        // part would corrupt the archive.
        val uploadPart: suspend (VolumePart) -> Boolean = { part ->
            var cleanSuccess = true
            if (remoteCleaned.not()) {
                // Before the first part goes up, purge stale remote archives of
                // this archive (old single file / different part count).
                remoteCleaned = true
                cleanSuccess = deleteRemoteArchives(client, remoteDstDir, baseName, suffix)
            }
            if (cleanSuccess) {
                val size = File(part.localPath).length()
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        client.upload(src = part.localPath, dst = remoteDstDir, onUploading = { _, _ -> })
                    }
                }
                if (result.isSuccess) {
                    uploadedIndexes.add(part.index)
                    uploadedBytes.addAndGet(size)
                    onUploading(uploadedBytes.get(), 0)
                    // "边上传边删除": free the disk space as soon as the part is
                    // safely on the cloud. Never delete on failure.
                    rootService.deleteRecursively(part.localPath)
                } else {
                    out.add(log { "Failed to upload: ${part.localPath}: ${result.exceptionOrNull()?.localizedMessage}" })
                }
                result.isSuccess
            } else {
                out.add(log { "Failed to clean stale remote archives in $remoteDstDir." })
                false
            }
        }

        if (useSingleFile) {
            // Single-file mode: run compression directly to a single archive,
            // clean up any stale remote volume parts from a previous volume-mode
            // backup, then upload the archive.
            producerCode = withContext(Dispatchers.IO) { BaseUtil.execute(fullCommand).code }
            if (producerCode == 0) {
                // Remove stale remote volume parts so a restore does not pick
                // them up and merge them with the new single-file archive.
                deleteRemoteVolumeParts(client, remoteDstDir, baseName, suffix)
                val size = File(singleFilePath).length()
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        client.upload(src = singleFilePath, dst = remoteDstDir, onUploading = { _, _ -> })
                    }
                }
                if (result.isSuccess) {
                    uploadedBytes.addAndGet(size)
                    onUploading(uploadedBytes.get(), 0)
                    rootService.deleteRecursively(singleFilePath)
                } else {
                    out.add(log { "Failed to upload: $singleFilePath: ${result.exceptionOrNull()?.localizedMessage}" })
                    producerCode = -1
                }
            } else {
                out.add(log { "Compression exited with code $producerCode, skip uploading." })
            }
        } else if (stream) {
            val producer = async(Dispatchers.IO) { BaseUtil.execute(fullCommand).code }
            var producerDone = false
            var nextIndex = 0

            while (true) {
                if (producerDone.not() && producer.isCompleted) {
                    producerDone = true
                    producerCode = producer.await()
                    if (producerCode != 0) {
                        out.add(log { "Compression exited with code $producerCode, aborting." })
                        aborted = true
                        break
                    }
                }

                val parts = listVolumeParts(dstDir, baseName, suffix)
                // Track parts by their volume index instead of their position in
                // the list: uploaded parts are deleted locally, so positions
                // shift while indexes stay stable.
                val lastIndex = parts.lastOrNull()?.index ?: -1
                // While the producer is still running, the highest-numbered
                // part may be incomplete, so it is not uploaded yet.
                val safeLastIndex = if (producerDone) lastIndex else lastIndex - 1

                while (nextIndex <= safeLastIndex) {
                    val part = parts.firstOrNull { it.index == nextIndex }
                    if (part == null) {
                        // busybox split never leaves gaps; a missing part while
                        // lower/higher ones exist means it disappeared. Retry
                        // until the producer is done, then fail.
                        out.add(log { "Missing local volume part #$nextIndex." })
                        if (producerDone) aborted = true
                        break
                    }
                    if (uploadPart(part)) {
                        nextIndex++
                    } else {
                        out.add(log { "Aborting: ${part.localPath} is not on the cloud and the archive would be incomplete." })
                        aborted = true
                        break
                    }
                }

                if (aborted) break
                // The producer finished and every finalized part has been
                // uploaded and deleted locally. Also covers a zero-part output.
                if (producerDone && nextIndex > lastIndex) break
                delay(POLL_INTERVAL_MS)
            }

            if (aborted && producer.isActive) {
                // The compression shell is busy with the tar|split pipeline and
                // can not be interrupted directly; kill the splitter via a
                // separate shell so tar/zstd terminate on SIGPIPE and stop
                // filling the disk. Depending on the busybox build the process
                // shows up as "split" or "busybox", so try both. Then wait for
                // the producer to return.
                out.add(log { "Trying to stop the compression pipeline..." })
                BaseUtil.kill(context, "split")
                BaseUtil.kill(context, "busybox")
                runCatching { producerCode = producer.await() }
            }
        } else {
            producerCode = withContext(Dispatchers.IO) { BaseUtil.execute(fullCommand).code }
            if (producerCode == 0) {
                listVolumeParts(dstDir, baseName, suffix).forEach { part ->
                    if (aborted.not()) {
                        if (uploadPart(part).not()) {
                            out.add(log { "Aborting: ${part.localPath} is not on the cloud and the archive would be incomplete." })
                            aborted = true
                        }
                    }
                }
            } else {
                out.add(log { "Compression exited with code $producerCode, skip uploading." })
            }
        }

        // The whole run failed: remove the parts that already reached the
        // cloud. A partial archive on the server would fail (or silently
        // corrupt) a future restore that merges it.
        val isSuccess = producerCode == 0 && aborted.not()

        // Degenerate case: the compression produced zero volume parts, so no
        // upload ever ran and the remote was never cleaned. Still purge stale
        // remote archives so a restore can not pick up an old backup.
        if (isSuccess && remoteCleaned.not()) {
            remoteCleaned = true
            deleteRemoteArchives(client, remoteDstDir, baseName, suffix).also {
                if (it.not()) out.add(log { "Failed to clean stale remote archives in $remoteDstDir." })
            }
        }

        if (isSuccess.not() && uploadedIndexes.isNotEmpty()) {
            uploadedIndexes.forEach { index ->
                val remotePath = "$remoteDstDir/${volumePartFileName(baseName, suffix, index)}"
                runCatching { withContext(Dispatchers.IO) { client.deleteFile(remotePath) } }
                    .onSuccess { out.add(log { "Removed incomplete remote part: $remotePath" }) }
                    .onFailure { out.add(log { "Failed to remove incomplete remote part: $remotePath" }) }
            }
        }

        ShellResult(
            code = if (isSuccess) 0 else -1,
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
        onDownloading: (written: Long, total: Long) -> Unit = { _, _ -> },
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
        } else if (parts.map { it.index } != parts.indices.toList()) {
            // A missing or extra part index (e.g. leftovers of an older backup
            // with more parts) would silently corrupt the merged archive.
            isSuccess = false
            out.add(log { "Volume part indexes are not contiguous: expected 0..${parts.size - 1}, got ${parts.map { it.index }}. The remote archive is incomplete or mixed with parts of another backup." })
        }

        if (isSuccess) {
            // Create the merge dir BEFORE fixing up SELinux/ownership, so that
            // the freshly created dir is also covered and the app process can
            // write the downloaded parts into it.
            rootService.mkdirs(dstDir)
            val mergeDir = "$dstDir/.volume_merge"
            rootService.deleteRecursively(mergeDir)
            rootService.mkdirs(mergeDir)
            PathUtil.setFilesDirSELinux(context)

            val downloadedBytes = AtomicLong(0)
            parts.forEach { part ->
                val remotePath = "$srcDir/${volumePartFileName(baseName, suffix, part.index)}"
                try {
                    withContext(Dispatchers.IO) {
                        client.download(src = remotePath, dst = mergeDir) { written, _ ->
                            onDownloading(downloadedBytes.get() + written, 0)
                        }
                    }
                    downloadedBytes.addAndGet(File("$mergeDir/${volumePartFileName(baseName, suffix, part.index)}").length())
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
