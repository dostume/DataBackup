package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.toPathList
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import com.xayah.libsardine.DavResource
import com.xayah.libsardine.impl.OkHttpSardine
import com.xayah.libsardine.impl.SardineException
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDAVClientImpl(private val entity: CloudEntity, private val extra: WebDAVExtra) : CloudClient {
    private var client: OkHttpSardine? = null

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "WebDAVClientImpl" to msg() }
        msg()
    }

    /**
     * Some WebDAV servers (notably AList mounted backends) reply 405 or 409
     * when asked to create/list a directory that already exists. Treat those
     * codes as "the path exists" instead of a fatal error.
     */
    private fun Throwable.isAlreadyExistsException(): Boolean {
        val code = (this as? SardineException)?.statusCode
        if (code == 405 || code == 409) return true
        val msg = localizedMessage ?: message ?: return false
        return msg.contains("405") || msg.contains("409")
    }

    /**
     * The host actually used for WebDAV requests. Starts as the configured
     * host and is corrected to the "/dav" endpoint during [connect] when the
     * configured host only serves the web frontend (OpenList/AList: their
     * web root answers 405 to PROPFIND, the real WebDAV lives under /dav).
     */
    private var davHost: String = entity.host.trimEnd('/')

    private fun getPath(path: String) = "$davHost/${path.trimStart('/')}"

    private fun withClient(block: (client: OkHttpSardine) -> Unit) = run {
        if (client == null) throw NullPointerException("Client is null.")
        block(client!!)
    }

    /**
     * True if this 405 came from a web frontend that only speaks GET/POST
     * (e.g. OpenList/AList). PROPFIND on their web root is answered 405 by
     * the SPA catch-all route, while the real WebDAV service lives under
     * the "/dav" prefix.
     */
    private fun Throwable.isWebFrontend405(): Boolean {
        val e = this as? SardineException ?: return false
        return e.statusCode == 405
    }

    override fun connect() {
        val builder = OkHttpClient.Builder()
            .connectTimeout(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
        if (extra.insecure) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())

                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
                // do nothing
            }
        }

        client = OkHttpSardine(builder.build()).apply {
            // Preemptive auth: send the Authorization header with every
            // request (PROPFIND/MKCOL/PUT/...). Servers that require auth to
            // list directories would otherwise answer 401 and hide the
            // remote tree (upstream issue #433).
            if (entity.user.isEmpty()) {
                setCredentials(entity.user, entity.pass)
            } else {
                setCredentials(entity.user, entity.pass, true)
            }

            // OpenList/AList and other gateways serve WebDAV under the /dav
            // prefix. If the configured host points at the web frontend,
            // PROPFIND is answered 405 by the SPA catch-all route. Retry
            // once with the /dav suffix so either form works.
            try {
                list(davHost)
            } catch (e: Exception) {
                if (e.isWebFrontend405() && !davHost.endsWith("/dav")) {
                    log { "connect: ${davHost} answered 405 to PROPFIND, retrying with /dav prefix..." }
                    val fallback = "${davHost}/dav"
                    try {
                        davHost = fallback
                        list(fallback)
                    } catch (e2: Exception) {
                        throw IOException(
                            "WebDAV endpoint not reachable: ${entity.host} answered 405 to PROPFIND " +
                                "(it looks like a web frontend such as OpenList/AList), and the /dav " +
                                "fallback also failed with: ${e2.localizedMessage}. " +
                                "Try setting the host to ${entity.host.trimEnd('/')}/dav explicitly.",
                            e2,
                        )
                    }
                } else {
                    throw e
                }
            }
        }
    }

    override fun disconnect() {
        client = null
    }

    override fun mkdir(dst: String) = withClient { client ->
        log { "mkdir: ${getPath(dst)}" }
        client.createDirectory(getPath(dst))
    }

    override fun mkdirRecursively(dst: String) = withClient { _ ->
        val dirs = dst.split("/")
        var currentDir = ""
        for (i in dirs) {
            if (i.isEmpty()) continue
            currentDir += "/$i"
            currentDir = currentDir.trimStart('/')
            if (exists(currentDir).not()) {
                runCatching { mkdir(currentDir) }.onFailure { e ->
                    if (e.isAlreadyExistsException()) {
                        // 405/409 can mean "already exists" on some servers, but it
                        // can also mean the server refuses directory creation
                        // entirely (e.g. a read-only WebDAV, or AList mounted with
                        // an unsupported backend). Confirm with a PROPFIND before
                        // treating it as success, otherwise the mistake surfaces
                        // later as confusing PUT 405 errors.
                        if (exists(currentDir)) {
                            log { "mkdirRecursively: ${getPath(currentDir)} already exists (${e.localizedMessage}), continue" }
                        } else {
                            throw IOException("Cannot create directory ${getPath(currentDir)}: ${e.localizedMessage}. " +
                                "The WebDAV server rejected MKCOL (HTTP 405/409) and the directory does not exist, " +
                                "which usually means the server does not support directory creation " +
                                "(e.g. AList mounted with a read-only or unsupported backend).")
                        }
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    override fun renameTo(src: String, dst: String) = withClient { client ->
        log { "renameTo: from ${getPath(src)} to ${getPath(dst)}" }
        client.move(getPath(src), getPath(dst), false)
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        if (name.isEmpty()) throw IllegalArgumentException("Upload source path is empty or ends with a slash: $src")
        val dstPath = "${getPath(dst)}/$name"
        log { "upload: $src to $dstPath" }
        val parent = PathUtil.getParentPath(dstPath.removePrefix(davHost))
        if (parent.isNotEmpty()) {
            runCatching { mkdirRecursively(parent) }.onFailure {
                log { "upload: failed to ensure parent dir $parent: ${it.localizedMessage}" }
            }
        }
        val srcFile = File(src)
        runCatching { client.put(dstPath, srcFile, null) }.onFailure { e ->
            if (e.isAlreadyExistsException()) {
                log { "upload: server rejected uploading to $dstPath (${e.localizedMessage}). " +
                    "HTTP 405 on PUT usually means the WebDAV server does not allow writes " +
                    "(e.g. AList mounted with a read-only or unsupported backend like 中国移动云盘)." }
            }
            throw e
        }
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "${dst}/$name"
        log { "download: ${getPath(src)} to $dstPath" }
        val dstOutputStream = File(dstPath).outputStream()
        val srcInputStream = client.get(getPath(src))
        srcInputStream.copyTo(dstOutputStream)
        srcInputStream.close()
        dstOutputStream.close()
    }

    override fun deleteFile(src: String) = withClient { client ->
        log { "deleteFile: ${getPath(src)}" }
        client.delete(getPath(src))
    }

    override fun removeDirectory(src: String) = withClient { client ->
        log { "removeDirectory: ${getPath(src)}" }
        client.delete(getPath(src))
    }

    private fun clearEmptyDirectoriesRecursivelyInternal(src: String): Boolean {
        var isEmpty = true
        withClient { client ->
            val resources: List<DavResource> = client.list(src)

            for (res in resources) {
                if (!res.isDirectory) {
                    isEmpty = false
                } else {
                    if (clearEmptyDirectoriesRecursivelyInternal(res.path).not()) {
                        isEmpty = false
                    }
                }
            }

            if (isEmpty) {
                client.delete(src)
            }

        }
        return isEmpty
    }


    override fun clearEmptyDirectoriesRecursively(src: String) {
        clearEmptyDirectoriesRecursivelyInternal(getPath(src))
    }

    override fun deleteRecursively(src: String) = removeDirectory(src)

    override fun listFiles(src: String): DirChildrenParcelable {
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        withClient { client ->
            val resources = client.list(getPath(src))
            for ((index, resource) in resources.withIndex()) {
                if (index == 0) continue
                val creationTime = runCatching { resource.creation.time }.getOrDefault(0)
                val fileParcelable = FileParcelable(resource.name, creationTime)
                if (resource.isDirectory) directories.add(fileParcelable)
                else files.add(fileParcelable)
            }
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    private fun walkFileTreeRecursively(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        val files = listFiles(src)
        for (i in files.files) {
            pathParcelableList.add(PathParcelable("${src}/${i.name}"))
        }
        for (i in files.directories) {
            pathParcelableList.addAll(walkFileTreeRecursively("${src}/${i.name}"))
        }
        return pathParcelableList
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        withClient { client ->
            val srcFile = client.list(getPath(src))[0]
            if (srcFile.isDirectory) {
                pathParcelableList.addAll(walkFileTreeRecursively(src))
            } else {
                pathParcelableList.add(PathParcelable(src))
            }
        }
        return pathParcelableList
    }

    override fun exists(src: String): Boolean = runCatching {
        withClient { client -> client.list(getPath(src)) }
    }.let { result ->
        if (result.isSuccess) {
            true
        } else {
            val e = result.exceptionOrNull()!!
            if (e.isAlreadyExistsException()) {
                log { "exists: ${getPath(src)} returned ${e.localizedMessage}, treating as true" }
                true
            } else {
                log { "exists: ${getPath(src)} failed with ${e.localizedMessage}" }
                false
            }
        }
    }

    private fun sizeRecursively(src: String): Long {
        var size = 0L
        withClient { client ->
            val files = listFiles(src)
            for (i in files.files) {
                size += client.list(getPath("${src}/${i.name}"))[0].contentLength
            }
            for (i in files.directories) {
                size("${src}/${i.name}")
            }
        }
        return size
    }

    override fun size(src: String): Long {
        var size = 0L
        withClient { client ->
            val srcFile = client.list(getPath(src))[0]
            size += if (srcFile.isDirectory) {
                sizeRecursively(src)
            } else {
                srcFile.contentLength
            }
        }
        log { "size: $size, $src" }
        return size
    }

    override suspend fun testConnection() {
        connect()
        disconnect()
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove “$Cloud:”
        pathSplit.removeFirstOrNull()
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<WebDAVExtra>()!!
        connect()
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching { mkdirRecursively(handleOriginalPath("$parent/$child")) }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            onSet(handleOriginalPath(pathString), GsonUtil().toJson(extra))
        }
        disconnect()
    }
}