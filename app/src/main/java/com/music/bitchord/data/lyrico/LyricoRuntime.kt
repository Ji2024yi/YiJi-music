package com.music.bitchord.data.lyrico

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lyrico 歌词插件的宿主。
 *
 * Lyrico（`Replica0110/Lyrico`，Apache 2.0）把「去哪找歌词」做成插件：一个
 * `manifest.json` 加一个 JS 入口，插件实现全局函数 `getLyrics(request)`，跑在
 * 嵌进 App 的 QuickJS 里 —— 跟本仓库已有的 module 音乐源是**同一个形状**
 * （QuickJS + manifest + 宿主 API），所以接它不需要新依赖，只需要把 Lyrico 那套
 * `Platform.*` 在这里提供出来。这样 Lyrico 生态的歌词插件能直接放进 BitChord 用，
 * 中文歌的命中率比内置那几个源高得多。
 *
 * ### 提供到哪一层
 *
 * 宿主 API 面很大（http / crypto / base64 / bytes / compression / xml / cache /
 * app / runtime）。这里接歌词插件实际会用的那几条：
 *
 *  `Platform.http` —— getText / postText / get / post / requestWithOption，全部
 *    **同步**：Lyrico 的插件就是这么写的（`var html = Platform.http.getText(url)`），
 *    所以走同步绑定，在这个 VM 线程上把请求做完；
 *  `crypto.md5` / `sha1` / `sha256` 与 AES-ECB/PKCS5 —— 国内平台的签名基本就这两样；
 *  `base64` 用纯 JS 实现（垫片里），不占宿主接口；
 *  `cache` 是内存 KV，进程活着就在，插件拿它存 cookie/token；
 *  `app` / `runtime` 只报身份和版本。
 *
 * 没接的：`xml`、`compression`、`bytes.*`，以及插件**返回 Promise** 的写法。需要
 * 它们的插件会在自己那一步抛错，错误原样出现在匹配结果里 —— 不静默吞，也不假装支持。
 *
 * ### 结果怎么落到歌词行
 *
 * `type` 决定读法：`structured` 是逐字数组（`[起, 止, [[字起, 字止, "字"]]]`），翻成
 * [LyricLine] + [LyricWord]；`rawPlainLrc` / `rawVerbatimLrc` / `rawEnhancedLrc` /
 * `rawMultiPersonEnhancedLrc` / `rawTtml` 一律交回
 * [EmbeddedLyrics.parseAny] —— LRC 还是 TTML 它自己认，不在这里再写一套判断。
 */
internal object LyricoRuntime {

    private const val TAG = "BitChord"

    /** 一个插件一个 VM：JS 顶层的 session/token 要活到下一次调用。 */
    private val engines = LinkedHashMap<String, QuickJs>()

    /** 每次调用的请求体走绑定传进去，不拼 JS 字面量 —— JSON 里的引号和反斜杠拼不干净。 */
    private val pendingRequest = HashMap<String, String>()

    /** 插件不再用时丢掉它的 VM（连带里面的 session）。 */
    fun unload(pluginId: String) {
        val qjs = synchronized(engines) { engines.remove(pluginId) }
        pendingRequest.remove(pluginId)
        runCatching { qjs?.close() }
    }

    /**
     * 这个脚本到底能不能做歌词 —— 建一个临时 VM 跑一遍，看有没有全局 `getLyrics`。
     *
     * 靠 manifest 的 capabilities 声明不算数：声明了却没实现的插件，要到匹配那一步
     * 才失败，而那时人已经在等结果了。探测跑一次真脚本，代价是导入时多一秒钟。
     */
    suspend fun canDoLyrics(pluginId: String, jsCode: String): Boolean =
        runCatching {
            val qjs = newEngine(pluginId, jsCode)
            runCatching { qjs.close() }
            true
        }.getOrElse {
            Log.d(TAG, "plugin $pluginId cannot do lyrics: ${it.message}")
            false
        }

    /**
     * 跑一个插件，拿它的候选。
     *
     * 失败不抛：插件里的一个空指针、一个超时，都只该让这一个来源退出这次匹配，
     * 不该把整件事弄停 —— 交回一个带 failure 的空列表，调用方按"这个源没结果"处理。
     */
    suspend fun lyrics(
        pluginId: String,
        jsCode: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        config: JSONObject,
    ): Result<List<LyricPluginResult>> = withContext(Dispatchers.Default) {
        runCatching {
            val qjs = engineFor(pluginId, jsCode)
            pendingRequest[pluginId] = JSONObject()
                .put(
                    "song",
                    JSONObject()
                        .put("id", "")
                        .put("title", title)
                        .put("artist", artist)
                        .put("album", album ?: JSONObject.NULL)
                        .put("duration", durationMs)
                        .put("sourceId", pluginId)
                        .put("pluginId", pluginId),
                )
                .put("page", 1)
                .put("pageSize", 20)
                .put("config", config)
                .toString()
            val raw = qjs.evaluate<String>(CALL)
            parseCandidates(raw)
        }
    }

    /** 一次调用的 JS：同步取 request、调 getLyrics、把结果 JSON 化。 */
    private val CALL = """
        var __lr_out = null;
        try {
          var __lr_request = JSON.parse(__lr.getRequest());
          var __lr_r = getLyrics(__lr_request);
          if (__lr_r && typeof __lr_r.then === 'function') {
            __lr_out = JSON.stringify({ __lr_failure: '这个插件返回的是 Promise，BitChord 的宿主目前只跑同步写法' });
          } else {
            __lr_out = JSON.stringify(__lr_r === undefined ? null : __lr_r);
          }
        } catch (__lr_e) {
          __lr_out = JSON.stringify({ __lr_failure: String(__lr_e && __lr_e.message ? __lr_e.message : __lr_e) });
        }
        __lr_out
    """.trimIndent()

    private suspend fun engineFor(pluginId: String, jsCode: String): QuickJs {
        synchronized(engines) { engines[pluginId] }?.let { return it }
        val built = newEngine(pluginId, jsCode)
        // 两个匹配同时落到同一个插件上时，两个人都会去建一个；谁先登记上就用谁的，
        // 后到的那个关掉，免得留一个没人认领的 VM。
        val winner = synchronized(engines) {
            val existing = engines[pluginId]
            if (existing != null) existing else engines.put(pluginId, built) ?: built
        }
        if (winner !== built) runCatching { built.close() }
        return winner
    }

    private suspend fun newEngine(pluginId: String, jsCode: String): QuickJs {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 512 * 1024L
        try {
            bindHost(qjs, pluginId)
            qjs.evaluate<String>(SHIM)
            // 插件脚本自己的顶层抛错要看得见，否则后面每次调用都只是"没有 getLyrics"。
            qjs.evaluate<String>(
                """
                var __lr_load_error = null;
                try {
                    $jsCode
                } catch (e) {
                    __lr_load_error = String(e && e.message ? e.message : e);
                }
                'ok'
                """.trimIndent(),
            )
            val loadError = qjs.evaluate<String>("__lr_load_error || 'none'")
            if (loadError != "none") throw IllegalStateException("插件加载失败：$loadError")
            if (qjs.evaluate<String>("typeof getLyrics") != "function") {
                throw IllegalStateException("这个插件没有 getLyrics，它不做歌词")
            }
            return qjs
        } catch (t: Throwable) {
            runCatching { qjs.close() }
            throw t
        }
    }

    /**
     * HTTP 用一条**独立**的短超时链：共享的 `Http.client` 读超时 30 秒，而插件里的
     * 一个卡死接口不该把整次匹配拖住 —— 批量跑几十首时尤其要紧。
     */
    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    // 宿主绑定 ----------------------------------------------------------

    private fun bindHost(qjs: QuickJs, pluginId: String) {
        qjs.define("__lr") {
            function("getRequest", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = pendingRequest[pluginId].orEmpty()
            })
            function("http", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = httpCall(
                    method = args.text(0),
                    url = args.text(1),
                    headersJson = args.text(2),
                    bodyBase64 = args.text(3),
                    connectMs = args.number(4),
                    readMs = args.number(5),
                )
            })
            function("md5", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = digestHex("MD5", args.text(0))
            })
            function("sha1", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = digestHex("SHA-1", args.text(0))
            })
            function("sha256", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = digestHex("SHA-256", args.text(0))
            })
            function("aesEncrypt", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    aesEcb(args.text(0), args.text(1), encrypt = true, base64Out = args.text(2) == "b64")
            })
            function("aesDecrypt", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    aesEcb(args.text(0), args.text(1), encrypt = false, base64Out = args.text(2) == "b64")
            })
            function("b64encodeText", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    java.util.Base64.getEncoder().encodeToString(args.text(0).toByteArray(Charsets.UTF_8))
            })
            function("b64decodeText", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    String(java.util.Base64.getMimeDecoder().decode(args.text(0)), Charsets.UTF_8)
            })
            function("info", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = JSONObject()
                    .put("name", "BitChord")
                    .put("versionName", "private")
                    .put("versionCode", 0)
                    .put("hostApiVersion", 4)
                    .put("userAgent", DEFAULT_AGENT)
                    .toString()
            })
            function("cacheGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String = LyricoCache.get(args.text(0)).orEmpty()
            })
            function("cacheSet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    LyricoCache.set(args.text(0), args.text(1), args.number(2))
                    return ""
                }
            })
            function("cacheRemove", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    LyricoCache.remove(args.text(0))
                    return ""
                }
            })
            function("notSupported", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    throw UnsupportedOperationException("BitChord 的 Lyrico 宿主暂未提供 ${args.text(0)}")
            })
        }
    }

    private fun Array<Any?>.text(index: Int): String = this.getOrNull(index)?.toString().orEmpty()

    private fun Array<Any?>.number(index: Int): Long =
        this.getOrNull(index)?.toString()?.toDoubleOrNull()?.toLong() ?: 0L

    private fun httpCall(
        method: String,
        url: String,
        headersJson: String,
        bodyBase64: String,
        connectMs: Long,
        readMs: Long,
    ): String {
        val headers = runCatching { JSONObject(headersJson) }.getOrNull() ?: JSONObject()
        val body = bodyBase64.takeIf { it.isNotEmpty() }
            ?.let { java.util.Base64.getMimeDecoder().decode(it) }
        val contentType = headers.optString("Content-Type", "application/octet-stream")
        val builder = Request.Builder().url(url)
        headers.keys().forEach { key ->
            if (!key.equals("Content-Length", ignoreCase = true)) builder.header(key, headers.optString(key))
        }
        if (builder.build().headers["User-Agent"].isNullOrBlank()) {
            builder.header("User-Agent", DEFAULT_AGENT)
        }
        val verb = method.uppercase().ifBlank { "GET" }
        val requestBody = when (verb) {
            "GET", "HEAD" -> null
            "DELETE" -> body?.toRequestBody(contentType.toMediaType())
            else -> (body ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
        }
        builder.method(verb, requestBody)
        val client = if (connectMs > 0L || readMs > 0L) {
            http.newBuilder()
                .connectTimeout(if (connectMs > 0) connectMs else 8_000, TimeUnit.MILLISECONDS)
                .readTimeout(if (readMs > 0) readMs else 12_000, TimeUnit.MILLISECONDS)
                .build()
        } else {
            http
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val headerMap = JSONObject()
                response.headers.toMultimap().forEach { (key, values) -> headerMap.put(key, values.joinToString(",")) }
                JSONObject()
                    .put("code", response.code)
                    .put("message", response.message)
                    .put("headers", headerMap)
                    // 文本和二进制都给：要不要当文本读是插件的事，宿主不去猜。
                    .put("bodyText", String(bytes, Charsets.UTF_8))
                    .put("bodyBase64", java.util.Base64.getEncoder().encodeToString(bytes))
                    .toString()
            }
        }.getOrElse { failure ->
            Log.d(TAG, "plugin http $url failed: ${failure.message}")
            JSONObject().put("code", 0).put("message", failure.message.orEmpty()).put("bodyText", "").toString()
        }
    }

    private fun digestHex(algorithm: String, text: String): String =
        java.security.MessageDigest.getInstance(algorithm)
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * AES/ECB/PKCS5Padding，输入输出都按 UTF-8 文本。
     *
     * 密钥直接用给到的字节：Lyrico 的插件里 key 就是 16 字符的居多，去补齐或截断
     * 反而改变它的语义，出了错也难查 —— 长度不对就让它抛，抛出来的错误看得见。
     */
    private fun aesEcb(text: String, key: String, encrypt: Boolean, base64Out: Boolean): String {
        val bytes = if (encrypt) {
            text.toByteArray(Charsets.UTF_8)
        } else {
            java.util.Base64.getMimeDecoder().decode(text)
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
        )
        val out = cipher.doFinal(bytes)
        return if (encrypt) {
            if (base64Out) java.util.Base64.getEncoder().encodeToString(out)
            else out.joinToString("") { "%02x".format(it) }
        } else {
            String(out, Charsets.UTF_8)
        }
    }

    // 结果解析 ----------------------------------------------------------

    /**
     * 插件交回来的东西摊成候选。
     *
     * API 4–5 是数组，1–3 可能是单个对象、一段裸文本，甚至 `null`；`__lr_failure`
     * 是我们自己包的一层错误，日志里说清楚就退出这一路。
     */
    internal fun parseCandidates(raw: String): List<LyricPluginResult> {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: JSONArray().put(runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList())
        val out = mutableListOf<LyricPluginResult>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                // 1–3 的插件允许直接 return 一整段歌词字符串。
                val text = array.optString(index)
                if (text.isNotBlank() && text.length > 20) {
                    out += LyricPluginResult(EmbeddedLyrics.parseAny(text), null, null, null, null)
                }
                continue
            }
            if (item.has("__lr_failure")) {
                Log.d(TAG, "lyrico plugin said: ${item.optString("__lr_failure")}")
                continue
            }
            val lines = linesOf(item) ?: continue
            if (lines.none { it.text.isNotBlank() }) continue
            val tags = item.optJSONObject("tags") ?: JSONObject()
            out += LyricPluginResult(
                lines = lines,
                title = tags.optString("ti").takeIf { it.isNotBlank() },
                artist = tags.optString("ar").takeIf { it.isNotBlank() },
                album = tags.optString("al").takeIf { it.isNotBlank() },
                year = tags.optString("date").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /** `type` 决定读法；raw 那几种一律交给 parseAny。 */
    private fun linesOf(item: JSONObject): List<LyricLine>? {
        for (field in RAW_FIELDS) {
            val text = item.optString(field).takeIf { it.isNotBlank() } ?: continue
            return EmbeddedLyrics.parseAny(text)
        }
        if (item.optString("type") == "structured") {
            val original = item.optJSONArray("original") ?: return null
            return structure(original)
        }
        return null
    }

    /**
     * structured 的一行是 `[起, 止, 词数组或整行文本]`。
     *
     * 词数组里每项是 `[字起, 字止, "字"]`；第三项直接给字符串的，是行级歌词，没有逐字。
     */
    private fun structure(original: JSONArray): List<LyricLine> =
        (0 until original.length()).mapNotNull { index ->
            val row = original.optJSONArray(index) ?: return@mapNotNull null
            val start = row.optLong(0)
            val end = if (row.length() > 1) row.optLong(1) else start
            when (val value = row.opt(2)) {
                is JSONArray -> {
                    val words = (0 until value.length()).mapNotNull { w ->
                        val word = value.optJSONArray(w) ?: return@mapNotNull null
                        LyricWord(word.optLong(0), word.optLong(1), word.optString(2))
                    }
                    LyricLine(
                        timeMs = start,
                        text = words.joinToString("") { it.text },
                        words = words,
                        sungUntilMs = end.takeIf { it > start },
                    )
                }
                is String -> LyricLine(
                    timeMs = start,
                    text = value,
                    sungUntilMs = end.takeIf { it > start },
                )
                else -> null
            }
        }

    private val RAW_FIELDS = listOf(
        "rawPlainLrc",
        "rawVerbatimLrc",
        "rawEnhancedLrc",
        "rawMultiPersonEnhancedLrc",
        "rawTtml",
    )

    private const val DEFAULT_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    /**
     * `Platform` 垫片。
     *
     * 全部同步 —— Lyrico 的插件就按同步写，而跑它的这一层已经在后台协程里，阻塞的
     * 只是这个 VM 自己的线程。base64 的字节↔数组在 JS 里做，不占宿主接口。
     */
    private val SHIM = """
        var global = globalThis; var window = globalThis;
        var __lr_B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        function __lr_bytesToB64(bytes) {
          var out = '';
          for (var i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i], b1 = bytes[i + 1], b2 = bytes[i + 2];
            out += __lr_B64.charAt(b0 >> 2);
            out += __lr_B64.charAt(((b0 & 3) << 4) | ((b1 === undefined ? 0 : b1) >> 4));
            out += (b1 === undefined) ? '=' : __lr_B64.charAt(((b1 & 15) << 2) | ((b2 === undefined ? 0 : b2) >> 6));
            out += (b2 === undefined) ? '=' : __lr_B64.charAt(b2 & 63);
          }
          return out;
        }
        function __lr_b64ToBytes(text) {
          var clean = String(text).replace(/[^A-Za-z0-9+/]/g, ''), out = [];
          for (var i = 0; i < clean.length; i += 4) {
            var n = (__lr_B64.indexOf(clean[i]) << 18) | (__lr_B64.indexOf(clean[i + 1]) << 12) |
                    ((__lr_B64.indexOf(clean[i + 2]) << 6) | __lr_B64.indexOf(clean[i + 3]));
            out.push((n >> 16) & 255, (n >> 8) & 255, n & 255);
          }
          var pad = String(text).length - String(text).replace(/=+$/, '').length;
          while (pad > 0 && out.length > 0) { out.pop(); pad--; }
          return out;
        }
        function __lr_bodyOf(options, body) {
          options = options || {};
          if (options.bodyBase64) return options.bodyBase64;
          if (options.bodyBytes) return __lr_bytesToB64(options.bodyBytes);
          if (typeof body === 'string' && body.length) return __lr.b64encodeText(body);
          if (options.body) return __lr.b64encodeText(String(options.body));
          return '';
        }
        function __lr_call(method, url, body, options) {
          options = options || {};
          var headers = options.headers || {};
          if (options.contentType) headers['Content-Type'] = options.contentType;
          var json = __lr.http(method, url, JSON.stringify(headers), __lr_bodyOf(options, body),
                               options.connectTimeoutMs || 0, options.readTimeoutMs || 0);
          return JSON.parse(json);
        }
        var Platform = {
          http: {
            getText: function (url, options) { return __lr_call('GET', url, null, options).bodyText; },
            postText: function (url, body, options) { return __lr_call('POST', url, body, options).bodyText; },
            getBytes: function (url, options) { return __lr_b64ToBytes(__lr_call('GET', url, null, options).bodyBase64); },
            postBytes: function (url, body, options) {
              var r = __lr_call('POST', url, body, options);
              return typeof body === 'string' ? r.bodyText : __lr_b64ToBytes(r.bodyBase64);
            },
            get: function (url, options) { return __lr_call('GET', url, null, options); },
            post: function (url, body, options) { return __lr_call('POST', url, body, options); },
            requestWithOption: function (options) {
              options = options || {};
              return __lr_call(options.method || 'GET', options.url || '', options.body, options);
            }
          },
          crypto: {
            md5: function (t) { return __lr.md5(String(t)); },
            sha1: function (t) { return __lr.sha1(String(t)); },
            sha256: function (t) { return __lr.sha256(String(t)); },
            aesEcbPkcs5EncryptBase64: function (t, k) { return __lr.aesEncrypt(String(t), String(k), 'b64'); },
            aesEcbPkcs5EncryptHex: function (t, k) { return __lr.aesEncrypt(String(t), String(k), 'hex'); },
            aesEcbPkcs5DecryptBase64ToText: function (t, k) { return __lr.aesDecrypt(String(t), String(k), 'b64'); }
          },
          base64: {
            encodeText: function (t) { return __lr.b64encodeText(String(t)); },
            decodeText: function (b) { return __lr.b64decodeText(String(b)); },
            encodeBytes: function (bs) { return __lr_bytesToB64(bs); },
            decodeBytes: function (b) { return __lr_b64ToBytes(b); },
            encodeUrlText: function (t) {
              return __lr.b64encodeText(String(t)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
            },
            decodeUrlText: function (b) {
              return __lr.b64decodeText(String(b).replace(/-/g, '+').replace(/_/g, '/'));
            },
            toUrl: function (b) { return String(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); },
            fromUrl: function (b) { return String(b).replace(/-/g, '+').replace(/_/g, '/'); }
          },
          cache: {
            get: function (k) { var v = __lr.cacheGet(String(k)); return v === '' ? null : v; },
            set: function (k, v, ttl) { __lr.cacheSet(String(k), String(v), ttl || 0); },
            remove: function (k) { __lr.cacheRemove(String(k)); },
            clear: function () {}
          },
          app: {
            getInfo: function () { return JSON.parse(__lr.info()); },
            getUserAgent: function () { return JSON.parse(__lr.info()).userAgent; }
          },
          runtime: {
            getInfo: function () { return { apiVersion: 5, hostApiVersion: 4, platform: 'android' }; }
          },
          bytes: {
            xor: function () { return __lr.notSupported('bytes.xor'); },
            xorBase64: function () { return __lr.notSupported('bytes.xorBase64'); }
          },
          compression: {
            inflateBase64ToText: function () { return __lr.notSupported('compression'); },
            inflateBytesToText: function () { return __lr.notSupported('compression'); }
          },
          xml: {
            query: function () { return __lr.notSupported('xml'); }
          }
        };
    """.trimIndent()
}

/** 一个插件候选：歌词行，加上它自己报名的字（那些字参与打分）。 */
data class LyricPluginResult(
    val lines: List<LyricLine>,
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
)

/**
 * 插件的 KV。进程内就够了：它存的是 cookie/token 这类东西，重启后本来也该重新取。
 *
 * `ttlMs` 为 0 表示永不过期（当前进程内）。上限防一个乱写的插件把内存吃掉。
 */
internal object LyricoCache {

    private const val MAX_ENTRIES = 512
    private val values = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun get(key: String): String? {
        val held = values[key] ?: return null
        if (held.second in 1 until System.currentTimeMillis()) {
            values.remove(key)
            return null
        }
        return held.first
    }

    @Synchronized
    fun set(key: String, value: String, ttlMs: Long) {
        if (values.size >= MAX_ENTRIES) values.clear()
        values[key] = value to if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0L
    }

    @Synchronized
    fun remove(key: String) {
        values.remove(key)
    }
}
