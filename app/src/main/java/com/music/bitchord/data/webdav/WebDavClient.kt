package com.music.bitchord.data.webdav

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.source
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal WebDAV client: PROPFIND directory listings over the shared
 * [Http.client], with HTTP Basic auth.
 *
 * Depth 1 is used per directory with manual recursion rather than
 * `Depth: infinity`, which many servers (notably Nextcloud) reject. XML is
 * parsed with the platform DOM parser so unit tests run on the JVM without
 * Android.
 */
object WebDavClient {

    data class Entry(
        val url: String,
        val displayName: String,
        val isCollection: Boolean,
        val contentType: String? = null,
    )

    private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontenttype/>
  </d:prop>
</d:propfind>
""".trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = WebDavConfig.normalizeUrl(url)
                require(WebDavConfig.isConfigured(target)) { "Enter a http(s) server URL" }
                val request = propfindRequest(target, depth = "0", username, password)
                Http.client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299 && response.code != 207) {
                        throw WebDavException("Server answered ${response.code}")
                    }
                }
            }
        }

    /**
     * Everything a library view needs: the audio plus the pictures filed
     * beside it. Covers travel as `cover.jpg`/`folder.jpg` next to the music
     * on every conventional server layout, and the listing that finds the
     * tracks already walks past them — a second pass would pay the whole
     * traversal again for files already in hand.
     */
    data class Listing(
        val audio: List<Entry>,
        val images: List<Entry>,
        /**
         * 同目录的歌词文件（.lrc / .ttml / .json3 / .xml / .txt）。
         *
         * 以前遍历只留音频和图片，歌词文件被当成「不是歌」扔掉了 —— 于是往服务
         * 器丢一份同名 .lrc 也不会有任何反应。默认空列表，老的解构调用方不受影响。
         */
        val lyrics: List<Entry> = emptyList(),
    )

    suspend fun listLibrary(
        baseUrl: String,
        username: String,
        password: String,
        maxFiles: Int = 10_000,
    ): Result<Listing> = withContext(Dispatchers.IO) {
        runCatching {
            val root = WebDavConfig.normalizeUrl(baseUrl)
            require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }
            val foundAudio = LinkedHashMap<String, Entry>()
            val foundImages = LinkedHashMap<String, Entry>()
            val foundLyrics = LinkedHashMap<String, Entry>()
            val dirs = ArrayDeque<String>()
            dirs.add(root)
            val visited = HashSet<String>()
            while (dirs.isNotEmpty() && foundAudio.size < maxFiles) {
                val dir = dirs.removeFirst()
                if (!visited.add(dir.lowercase())) continue
                val entries = propfind(dir, username, password).getOrThrow()
                for (entry in entries) {
                    if (entry.isCollection) {
                        if (!visited.contains(entry.url.lowercase())) dirs.add(entry.url)
                    } else {
                        val name = entry.displayName.ifBlank { entry.url }
                        when {
                            WebDavConfig.isAudioFile(name) -> foundAudio.putIfAbsent(entry.url, entry)
                            isImageFile(name) -> foundImages.putIfAbsent(entry.url, entry)
                            isLyricFile(name) -> foundLyrics.putIfAbsent(entry.url, entry)
                        }
                    }
                }
            }
            Listing(
                foundAudio.values.toList(),
                foundImages.values.toList(),
                foundLyrics.values.toList(),
            )
        }
    }

    suspend fun listAudioFiles(
        baseUrl: String,
        username: String,
        password: String,
        maxFiles: Int = 10_000,
    ): Result<List<Entry>> = listLibrary(baseUrl, username, password, maxFiles).map { it.audio }

    fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp"
    }

    /**
     * 服务器上跟着一首歌放的歌词文件。
     *
     * `.ttml` / `.json3` 是带逐字时间戳的那两种（Apple Music 的写法），`.lrc`
     * 是行级的，`.xml` 是 TTML 常挂的后缀，`.txt` 是不带时间戳的纯文本 ——
     * 主流开源播放器（洛雪、MusicFree 那一类）都按这一套认，所以他往目录里丢
     * 一个同名 .lrc 就该生效。`cover.jpg` 那种图片在上一个分支已经分走了。
     */
    fun isLyricFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in LYRIC_EXTENSIONS
    }

    private val LYRIC_EXTENSIONS = setOf("lrc", "ttml", "json3", "xml", "txt")

    private fun propfindRequest(
        url: String,
        depth: String,
        username: String,
        password: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Depth", depth)
            .method("PROPFIND", PROPFIND_BODY)
        WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
        return builder.build()
    }

    /**
     * Whether [fileUrl] already exists. Any answer other than 200/404 is a
     * failure rather than a guess — treating a 500 as "absent" is how an
     * upload silently eats a file it was told not to touch.
     */
    suspend fun exists(fileUrl: String, username: String, password: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(fileUrl).head()
                WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
                Http.client.newCall(builder.build()).execute().use { response ->
                    when (response.code) {
                        200 -> true
                        404 -> false
                        else -> throw WebDavException("Exists check failed with ${response.code}")
                    }
                }
            }
        }

    /**
     * 从服务器永久删除一个文件。
     *
     * 404 当作已经删掉——重试、或者另一台设备先删过，都不该报错。其余非 2xx
     * 一律抛给调用方，绝不静默失败。
     */
    suspend fun delete(fileUrl: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(fileUrl).delete()
                WebDavConfig.basicAuthHeader(username, password)
                    ?.let { builder.header("Authorization", it) }
                Http.client.newCall(builder.build()).execute().use { response ->
                    if (response.code !in 200..299 && response.code != 404) {
                        throw WebDavException("Delete failed with ${response.code}")
                    }
                }
            }
        }

    /**
     * WebDAV MOVE。
     *
     * 上传先写到 `.part`，成功后再 MOVE 成正式名字：中途断掉只会留下一个
     * 不会被曲库当成歌的临时文件，而不是一个播到一半没声音的半截 FLAC。
     */
    suspend fun move(
        fromUrl: String,
        toUrl: String,
        username: String,
        password: String,
        overwrite: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(fromUrl)
                .method("MOVE", null)
                .header("Destination", toUrl)
                .header("Overwrite", if (overwrite) "T" else "F")
            WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
            Http.client.newCall(builder.build()).execute().use { response ->
                if (response.code !in 200..299) {
                    throw WebDavException("Move failed with ${response.code}")
                }
            }
        }
    }

    sealed interface PutResult {
        data object Uploaded : PutResult
        /** The server refused a non-overwriting PUT because the file is there. */
        data object AlreadyExists : PutResult
    }

    /**
     * 上传专用 client。
     *
     * 共享 client 没设 writeTimeout，也就是 OkHttp 默认的 10 秒——一次 socket
     * 写入 10 秒没有进展就断。一个 40MB 的 FLAC 在移动网络上一次停顿超过 10 秒
     * 很常见，断了以后 dufs 那头读到半截（"error reading a body from
     * connection"），客户端拿到 400/500，看起来就是「上传不了」。
     */
    private val uploadClient: okhttp3.OkHttpClient by lazy {
        Http.client.newBuilder()
            .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            // 不自动重试：请求体是一次性的流，重试只会把已关闭的流再发一遍。
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Streams [stream] to [fileUrl] without buffering it in memory — a FLAC
     * can be a hundred megabytes, and holding two copies (ours plus OkHttp's)
     * is what gets a background upload killed.
     *
     * [contentLength] may be -1 when the size isn't knowable up front (a
     * `content://` row that won't say); OkHttp then sends chunked, which
     * Nextcloud and every RFC-compliant server accepts for PUT.
     *
     * Without [overwrite], `If-None-Match: *` makes the absence check atomic:
     * a 412 comes back instead of a clobbered file when something else won
     * the race between [exists] and this call.
     */
    suspend fun putFile(
        fileUrl: String,
        stream: InputStream,
        contentLength: Long,
        mimeType: String,
        username: String,
        password: String,
        overwrite: Boolean,
        onProgress: ((bytesWritten: Long) -> Unit)? = null,
    ): Result<PutResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = StreamBody(stream, contentLength, mimeType.toMediaType(), onProgress)
            val builder = Request.Builder().url(fileUrl).put(body)
            if (!overwrite) builder.header("If-None-Match", "*")
            WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
            uploadClient.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> PutResult.Uploaded
                    412 -> PutResult.AlreadyExists
                    else -> throw WebDavException("Upload failed with ${response.code}")
                }
            }
        }
    }

    /**
     * Creates the collection at [dirUrl], if it isn't one already. 405 means
     * something is already there, which is the happy path wearing an error
     * code; anything else out of 2xx is a real failure.
     */
    /**
     * 把远程文件流式写进 [sink]，边写边回报字节数。
     *
     * 和 [putFile] 对称的那一半：上传早就有，"把服务器上的歌存到手机"没有——长按
     * WebDAV 行点"下载"原本走 YouTube 那条解析流，对远程文件不成立。这里直接对
     * https 直链发 GET，交给 [WebDavDownloads] 落盘。
     */
    suspend fun downloadToFile(
        fileUrl: String,
        username: String,
        password: String,
        sink: java.io.OutputStream,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(fileUrl).get()
            WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
            Http.client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw WebDavException("Download failed with " + response.code)
                val body = response.body ?: throw WebDavException("The server sent no body")
                val total = body.contentLength().coerceAtLeast(0L)
                var written = 0L
                body.byteStream().use { input ->
                    sink.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                        out.flush()
                    }
                }
                written
            }
        }
    }

    suspend fun ensureCollection(dirUrl: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(dirUrl).method("MKCOL", null)
                WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
                Http.client.newCall(builder.build()).execute().use { response ->
                    if (response.code !in 200..299 && response.code != 405) {
                        throw WebDavException("Could not create folder (${response.code})")
                    }
                }
            }
        }

    private fun propfind(dirUrl: String, username: String, password: String): Result<List<Entry>> {
        return runCatching {
            val request = propfindRequest(dirUrl, depth = "1", username, password)
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 207 && response.code !in 200..299) {
                    throw WebDavException("Listing failed with ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching emptyList()
                parseMultistatus(body, dirUrl)
            }
        }
    }

    /**
     * Parses a PROPFIND multistatus response. Pure function of the XML and the
     * directory it was asked of — the entry the server echoes back for the
     * directory itself is dropped.
     */
    fun parseMultistatus(xml: String, dirUrl: String): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val out = ArrayList<Entry>(responses.length)
        for (i in 0 until responses.length) {
            val node = responses.item(i) as? Element ?: continue
            val href = node.getElementsByTagNameNS("*", "href").item(0)?.textContent?.trim().orEmpty()
            if (href.isEmpty()) continue
            val url = resolveHref(dirUrl, href)
            if (isSameUrl(url, dirUrl)) continue
            val displayName = node.getElementsByTagNameNS("*", "displayname")
                .item(0)?.textContent?.trim().orEmpty()
                .ifBlank { decodedName(url) }
            val resourceType = node.getElementsByTagNameNS("*", "resourcetype").item(0) as? Element
            val isCollection = resourceType
                ?.getElementsByTagNameNS("*", "collection")?.length?.let { it > 0 } == true
            val contentType = node.getElementsByTagNameNS("*", "getcontenttype")
                .item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
            out.add(Entry(url = url, displayName = displayName, isCollection = isCollection, contentType = contentType))
        }
        return out
    }

    fun resolveHref(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = WebDavConfig.normalizeUrl(baseUrl)
        val schemeHost = base.substringBefore('/', "").let {
            // Keep "https://host" (and port) only.
            val schemeEnd = base.indexOf("://")
            if (schemeEnd < 0) return trimmed
            val pathStart = base.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) base else base.substring(0, pathStart)
        }
        return if (trimmed.startsWith("/")) "$schemeHost$trimmed" else "$base/$trimmed"
    }

    private fun isSameUrl(a: String, b: String): Boolean {
        fun norm(u: String) = u.trim().trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private fun decodedName(url: String): String = runCatching {
        URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")
    }.getOrDefault(url.trimEnd('/').substringAfterLast('/'))

    /**
     * `root/<segment>`, with the segment percent-encoded. `URLEncoder` is a
     * form encoder, so `+` (space) is rewritten to `%20` — a path is not a
     * query string.
     */
    fun joinUrl(root: String, segment: String): String {
        val encoded = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        return "${root.trimEnd('/')}/$encoded"
    }

    /**
     * The name a track is stored under: `Artist - Title.ext`, or just the
     * title when there is no artist worth naming. Filesystem-hostile
     * characters never reach the server — a `/` in a title would otherwise
     * file it into a folder nobody asked for.
     */
    fun uploadFileName(title: String, artist: String, extension: String): String {
        val rawBase = if (artist.isNotBlank() && artist != "Unknown Artist") "$artist - $title" else title
        val clean = rawBase
            .map { c -> if (c == '/' || c == '\\' || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim().trim('.')
            .take(120)
            .ifBlank { "track" }
        val ext = extension.lowercase(Locale.ROOT).trimStart('.').takeIf { it.isNotBlank() } ?: "mp3"
        return "$clean.$ext"
    }

    /**
     * First name in `base`, `base (1)`, `base (2)`, … that isn't in [taken].
     * Compared case-insensitively: servers that ignore case would otherwise
     * hand back a "new" name that collides on disk.
     */
    fun resolveNumberedName(base: String, extension: String, taken: Set<String>): String {
        val lowered = taken.map { it.lowercase(Locale.ROOT) }.toSet()
        var candidate = "$base.$extension"
        var n = 1
        while (candidate.lowercase(Locale.ROOT) in lowered && n < 1000) {
            candidate = "$base ($n).$extension"
            n++
        }
        return candidate
    }

    class WebDavException(message: String) : Exception(message)

    private class StreamBody(
        private val stream: InputStream,
        private val length: Long,
        private val mime: MediaType?,
        private val onProgress: ((Long) -> Unit)?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mime
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            stream.use { input ->
                val source: Source = input.source()
                source.buffer().use { buffered ->
                    var read: Long
                    while (buffered.read(sink.buffer, 8192).also { read = it } != -1L) {
                        written += read
                        // 不再每 8KB 显式 flush。OkHttp 的 buffer
                        // 满一个 segment 自己会发，逐块 flush 只会把一次上传拆成
                        // 几千个小写，移动网络上更容易被中间设备掐断。
                        onProgress?.invoke(written)
                    }
                }
            }
            if (length >= 0 && written != length) {
                throw IOException("Short upload: wrote $written of $length bytes")
            }
        }
    }
}
