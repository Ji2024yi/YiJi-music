package com.music.bitchord.data.webdav

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Copies on-device tracks to the configured WebDAV server. Copy, not move:
 * the local file stays where it was, so this never needs delete permission
 * and never rewrites the Downloads record — the server copy simply shows up
 * in the WebDAV library on its next listing.
 *
 * One batch runs sequentially on IO. Parallel PUTs would finish sooner on
 * Wi-Fi and fall over on a metered connection, and ordering is what makes a
 * multi-conflict batch answerable: each clash suspends on [conflict], the
 * activity's dialog answers it, and an "apply to all" answer carries the rest
 * of the batch without another popup.
 */
object WebDavUploads {

    sealed interface TrackState {
        data object Queued : TrackState
        data class Running(val fraction: Float) : TrackState
        data object Done : TrackState
        data class Failed(val reason: String) : TrackState
        data object Skipped : TrackState
    }

    enum class Choice { OVERWRITE, KEEP_BOTH, SKIP }

    data class Resolution(val choice: Choice, val applyToAll: Boolean)

    data class ConflictRequest(
        val song: Song,
        val fileName: String,
        /** Tracks still queued behind this one — whether "apply to all" means anything. */
        val remaining: Int,
        val answer: CompletableDeferred<Resolution>,
    )

    data class Summary(val uploaded: Int, val skipped: Int, val failed: Int) {
        val total: Int get() = uploaded + skipped + failed
    }

    private val _active = MutableStateFlow<Map<String, TrackState>>(emptyMap())
    val active: StateFlow<Map<String, TrackState>> = _active.asStateFlow()

    /**
     * The clash waiting on the listener, or null when the batch is flowing.
     * Rendered by the activity — the uploader itself is headless.
     */
    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict.asStateFlow()

    /**
     * Whether [song] can be sent at all: a file on this device, not a track
     * already living on the server and not a remote stream wearing a
     * `localUri`. Readability itself is checked at upload time and reported
     * per track — this is only the cheap gate for showing the row.
     */
    fun isUploadable(song: Song): Boolean {
        if (WebDavConfig.isWebDavId(song.videoId)) return false
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return uri.scheme == "content" || uri.scheme == "file"
    }

    suspend fun upload(context: Context, songs: List<Song>): Summary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val url = com.music.bitchord.data.settings.AppSettings.webdavUrl.value
        val username = com.music.bitchord.data.settings.AppSettings.webdavUsername.value
        val password = com.music.bitchord.data.settings.AppSettings.webdavPassword.value
        val root = WebDavConfig.normalizeUrl(url)
        require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }

        val queue = songs.filter { isUploadable(it) }
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var standing: Resolution? = null
        // Names claimed by this batch, so two locals with the same title
        // don't both aim at one target.
        val taken = HashSet<String>()

        if (queue.isEmpty()) {
            WebDavUploadNotifications.cancel(appContext)
            return@withContext Summary(0, 0, 0)
        }
        // Fresh state for the tracks about to run; anything left over from an
        // earlier batch for them (a Retry from a failure) is stale.
        setAll(queue.map { it.videoId }, null)
        WebDavUploadNotifications.postProgress(appContext, null, 0, queue.size, null)
        for ((index, song) in queue.withIndex()) {
            set(song.videoId, TrackState.Queued)
            val remaining = queue.size - index - 1
            WebDavUploadNotifications.postProgress(appContext, song, 1, remaining, null)
            runCatching {
                uploadOne(appContext, song, root, username, password, taken, remaining) { req ->
                    standing?.let { return@uploadOne it }
                    _conflict.value = req
                    try {
                        req.answer.await().also { if (it.applyToAll) standing = it }
                    } finally {
                        _conflict.value = null
                    }
                }
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        Outcome.Uploaded -> {
                            set(song.videoId, TrackState.Done)
                            uploaded++
                        }
                        Outcome.Skipped -> {
                            set(song.videoId, TrackState.Skipped)
                            skipped++
                        }
                    }
                },
                onFailure = {
                    set(song.videoId, TrackState.Failed(it.message ?: "Upload failed"))
                    failed++
                },
            )
        }
        val summary = Summary(uploaded, skipped, failed)
        // Replaces the progress row in the shade with the batch result.
        WebDavUploadNotifications.postDone(appContext, summary)
        // The library page lists the server, so a batch that landed while it
        // is open has to be re-read to show up.
        summary
    }

    private enum class Outcome { Uploaded, Skipped }

    private suspend fun uploadOne(
        context: Context,
        song: Song,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val ext = extensionFor(context, song)
        val sendable = prepareUpload(context, song, ext)
        try {
            return sendBytes(context, song, sendable.source, ext, root, username, password, taken, remaining, resolve)
        } finally {
            sendable.discard()
        }
    }

    private data class Sendable(val source: UploadSource, val discard: () -> Unit)

    /**
     * What actually goes up the wire: the file itself, or — when it is one of
     * this app's downloads saved without its cover — a staged copy with the
     * cover topped up first.
     *
     * The gates matter more than the machinery. Only [MediaTagger]'s three
     * containers are touched, only files already carrying no picture are
     * candidates, and only downloads qualify: their tags are catalogue values
     * this app wrote, so topping up a missing cover cannot clobber anything
     * the listener authored. A hand-tagged rip keeps its bytes exactly.
     *
     * Best-effort throughout: any failure falls back to the file as it stands,
     * because a missing cover must never fail an upload.
     */
    private suspend fun prepareUpload(context: Context, song: Song, ext: String): Sendable {
        val original = openSource(context, song) ?: throw WebDavException("Could not read this file")
        // 不管要不要重打标签，都先落到一个临时文件。
        // content:// 的流是一次性的，长度还常常是 MediaStore 报的（不一定等于
        // 真正能读出来的字节数）；一旦 HTTP 栈重试、或者长度对不上，连接就会在
        // 半路被掐掉，服务器那头看到的是 "error reading a body from connection"。
        // 落到本地文件以后，长度就是文件长度，流也随时能重开。
        val staged = File.createTempFile("webdav-upload", ".tmp", context.cacheDir)
        val copied = runCatching {
            original.stream.use { input -> staged.outputStream().use { input.copyTo(it) } }
        }.isSuccess && staged.length() > 0
        if (!copied) {
            staged.delete()
            throw WebDavException("Could not read this file")
        }

        // Tagging stays best-effort: a missing cover or an unrewritable container
        // leaves the staged copy exactly as it came off the device.
        if (com.music.bitchord.download.MediaTagger.carriesTags(ext) &&
            !hasEmbeddedPicture(context, song) &&
            com.music.bitchord.download.Downloads.verifiedSavedUri(song.videoId) != null
        ) {
            val artwork = runCatching {
                com.music.bitchord.download.MediaTagger.artworkFor(context, song)
            }.getOrNull()
            if (artwork != null) {
                // 重写标签会整块替换 VORBIS_COMMENT / ilst，只写
                // 封面会把原来的 LYRICS 一起丢掉。先把本地歌词读出来再一起写回。
                val existingLyrics = song.localUri?.let { uri ->
                    runCatching { com.music.bitchord.data.lyrics.EmbeddedLyrics.rawForUri(context, uri) }
                        .getOrNull()
                }?.takeIf { it.isNotBlank() }?.let {
                    com.music.bitchord.download.LyricsTag.Embeddable(it, null)
                }
                runCatching {
                    com.music.bitchord.download.MediaTagger.embed(
                        context,
                        Uri.fromFile(staged),
                        song,
                        ext,
                        lyrics = existingLyrics,
                        artwork = artwork,
                    )
                }
            }
        }

        val stream = runCatching { staged.inputStream() }.getOrNull()
            ?: run { staged.delete(); throw WebDavException("Could not read this file") }
        return Sendable(
            UploadSource(stream, staged.length()),
        ) {
            runCatching { stream.close() }
            staged.delete()
        }
    }

    private fun hasEmbeddedPicture(context: Context, song: Song): Boolean {
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        val retriever = android.media.MediaMetadataRetriever()
        return runCatching {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture != null
        }.getOrDefault(false).also { runCatching { retriever.release() } }
    }

    private suspend fun sendBytes(
        context: Context,
        song: Song,
        source: UploadSource,
        ext: String,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val base = WebDavClient.uploadFileName(song.title, song.artist, ext)
            .removeSuffix(".$ext")
        // In-batch collisions are numbered without troubling the server.
        var fileName = WebDavClient.resolveNumberedName(base, ext, taken)
        taken.add(fileName.lowercase(Locale.ROOT))

        var target = WebDavClient.joinUrl(root, fileName)
        // Only an overwrite writes over an existing file; every other path
        // keeps `If-None-Match`, so a race after the check below 412s instead
        // of clobbering.
        var overwrite = false
        val alreadyThere = WebDavClient.exists(target, username, password).getOrThrow()
        if (alreadyThere) {
            when (resolve(ConflictRequest(song, fileName, remaining, CompletableDeferred())).choice) {
                Choice.SKIP -> return Outcome.Skipped
                Choice.OVERWRITE -> overwrite = true
                Choice.KEEP_BOTH -> {
                    // Number past whatever the server holds, checking each
                    // candidate — the batch set above only knows about itself.
                    var attempts = 0
                    while (attempts++ < 25) {
                        taken.add(fileName.lowercase(Locale.ROOT))
                        fileName = WebDavClient.resolveNumberedName(base, ext, taken)
                        target = WebDavClient.joinUrl(root, fileName)
                        if (!WebDavClient.exists(target, username, password).getOrThrow()) break
                    }
                    taken.add(fileName.lowercase(Locale.ROOT))
                }
            }
        }
        val (stream, length) = source
        // 先写 `<name>.part`，成功再 MOVE 成正式名。上传中断时
        // 服务器上只会留下一个 .part（曲库不认它是音频），不会再出现「播到一半
        // 没声音」的半截 FLAC——那正是之前 26MB/53MB 那个文件的样子。
        val partUrl = "$target.part"
        // The shade coalesces anything faster anyway, and a notification per
        // 8KB segment is just binder spam — one post per percentage point.
        var lastPostedPercent = -1
        val uploaded = runCatching {
            WebDavClient.putFile(
                fileUrl = partUrl,
                stream = stream,
                contentLength = length,
                mimeType = mimeFor(ext),
                username = username,
                password = password,
                overwrite = true,
                onProgress = { written ->
                    if (length <= 0) return@putFile
                    val fraction = (written.toFloat() / length).coerceIn(0f, 1f)
                    set(song.videoId, TrackState.Running(fraction))
                    val percent = (fraction * 100).toInt()
                    if (percent != lastPostedPercent) {
                        lastPostedPercent = percent
                        WebDavUploadNotifications.postProgress(context, song, 1, remaining, fraction)
                    }
                },
            ).getOrThrow()
        }
        uploaded.fold(
            onSuccess = {
                runCatching { WebDavClient.move(partUrl, target, username, password, overwrite = overwrite) }
                    .onFailure { cleanup(partUrl, username, password) }
                    .getOrThrow()
                return Outcome.Uploaded
            },
            onFailure = { error ->
                cleanup(partUrl, username, password)
                throw error
            },
        )
        return Outcome.Skipped
    }

    private data class UploadSource(val stream: InputStream, val length: Long)

    /** Best-effort removal of a half-written `.part`; its own failure is not news. */
    private suspend fun cleanup(url: String, username: String, password: String) {
        runCatching { WebDavClient.delete(url, username, password) }
    }

    private fun openSource(context: Context, song: Song): UploadSource? {
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return null)
                    if (!file.isFile || !file.canRead()) return null
                    UploadSource(file.inputStream(), file.length().takeIf { it > 0 } ?: -1)
                }
                "content" -> {
                    val resolver = context.contentResolver
                    val stream = resolver.openInputStream(uri) ?: return null
                    UploadSource(stream, querySize(resolver, uri))
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = cursor.getLong(0)
                if (size > 0) return size
            }
        }
        return -1
    }

    internal fun extensionFor(context: Context, song: Song): String {
        val fromPath = song.localPath
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in WebDavConfig.audioExtensions }
        if (fromPath != null) return fromPath
        val displayName = song.localUri
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" }
            ?.let { uri ->
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
        return displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in WebDavConfig.audioExtensions }
            ?: "mp3"
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private fun set(videoId: String, state: TrackState?) {
        _active.value = _active.value.let { current ->
            if (state == null) current - videoId else current + (videoId to state)
        }
    }

    private fun setAll(videoIds: List<String>, state: TrackState?) {
        _active.value = _active.value.let { current ->
            var next = current
            for (id in videoIds) next = if (state == null) next - id else next + (id to state)
            next
        }
    }

    class WebDavException(message: String) : Exception(message)
}
