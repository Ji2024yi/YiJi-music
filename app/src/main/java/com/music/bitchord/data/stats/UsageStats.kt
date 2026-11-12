package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 匿名使用统计（只算日活与留存）。
 *
 * 契约与 `deploy/stats_service.py` 的 `validate_event` / 回执逐条对齐：
 *
 * * 端点 `POST {BASE_URL}/stats/v1/events`
 * * 请求体 `{"schema_version":1,"events":[…]}`
 * * 字段白名单：sid / type / ts / day / ver / code / brand / model / os / api。
 *   **多一个键整条被拒**，所以本地标记（att）在发送前会被剥掉。
 * * 回执 `{"accepted":n,"duplicated":n,"rejected":n,"rejects":[{"i":k,"reason":…}]}`：
 *   按下标判——落在 `rejects` 里的进死信，其余（含 duplicated）出队。
 * * 授权：首次启动二选一，**不表态就一条都不采**，队列文件都不创建。
 */
object UsageStats {

    enum class Type(val wire: String) {
        /** 每次进程冷启动一条。 */
        LAUNCH("launch"),

        /** **DAU 的唯一来源**。每天一条，服务端确认收到后才落盘标记。 */
        ACTIVE("active"),

        /** 从后台回前台（≥30 秒去抖，冷启动不重复计）。 */
        FOREGROUND("foreground"),

        /** 用户关掉开关那一刻的告别事件，只发一次、不落盘。 */
        OPT_OUT("opt_out"),
    }

    private const val TAG = "UsageStats"

    /**
     * 统计服务地址。必须改成你自己的，**不要带尾斜杠**。
     *
     * 留 `example.com` 时整个模块不工作：不建队列、不发请求。这样即使漏了配置，
     * 也不会有请求打到一个不存在的域名上。
     */
    private const val BASE_URL = "https://stats.example.com"

    /**
     * 证书钉扎：服务端证书的 **SPKI** sha256（不是证书文件 sha256）。
     *
     * ```
     * openssl x509 -in fullchain.pem -pubkey -noout \
     *   | openssl pkey -pubin -outform DER \
     *   | openssl dgst -sha256 -binary | base64
     * ```
     *
     * 留空 = 只走系统 CA 校验。钉扎时**至少两张**（当前 + 备用），否则一次续签
     * 会让所有客户端同时失联。
     */
    private val SPKI_PINS = emptyList<String>()

    private const val PATH = "/stats/v1/events"

    /** 服务端已确认收到 active 的日期。换 key 是为了不读旧版留下的「入队即确认」脏值。 */
    private const val KEY_CONFIRMED_ACTIVE_DAY = "confirmed_active_day"
    private const val MAX_BATCH = 200
    private const val MAX_BATCHES_PER_FLUSH = 4
    private const val MAX_QUEUE = 500
    private const val MAX_DEAD = 200
    // 只用来兜住「服务端明确不要这条」之外的失败。网络抖动、证书切换、
    // 服务器重启都可能连着失败好几次，别在五分钟内就把事件丢进死信。
    private const val MAX_ATTEMPTS = 20
    private const val MAX_QUEUE_BYTES = 512 * 1024L

    /** 中国时区固定 UTC+8，与服务端 `day_str` 同一口径。 */
    private const val TZ_OFFSET_HOURS = 8

    private val CONTRACT_FIELDS = listOf(
        "sid", "type", "ts", "day", "ver", "code", "brand", "model", "os", "api",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushing = AtomicBoolean(false)

    @Volatile
    private var installed = false

    @Volatile
    private var lastForegroundAt = 0L

    /** 本进程已经产出过 active 的日期；进程重启即清空，不落盘。 */
    @Volatile
    private var activeSentDay: String? = null

    @Volatile
    private var appContext: Context? = null

    private lateinit var dir: File

    private val queueFile: File get() = File(dir, "queue.jsonl")
    private val deadFile: File get() = File(dir, "dead.jsonl")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .apply {
                if (SPKI_PINS.isNotEmpty()) {
                    // OkHttp 的 pin 按纯主机名匹配，端口必须去掉；写成
                    // 「主机名:端口」 会让 pattern 要么非法、要么永远匹配不上。
                    val host = java.net.URI(BASE_URL).host
                    certificatePinner(
                        CertificatePinner.Builder().apply {
                            SPKI_PINS.forEach { add(host, it) }
                        }.build(),
                    )
                }
            }
            .build()
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** `Application.onCreate` 里调一次。没授权时什么都不做。 */
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        dir = File(context.filesDir, "stats")
        installed = true
        if (!allowed()) return
        dir.mkdirs()
        track(Type.LAUNCH)
        reportActive()
        scheduleFlush(1_500)
    }

    /** 开关重新打开之后调，补上这次启动的事件。 */
    fun onConsentGranted(context: Context) {
        if (installed) {
            track(Type.LAUNCH)
            reportActive()
            scheduleFlush(800)
        } else {
            install(context)
        }
    }

    /** `Activity.onStart` 里调。30 秒内重复进入不算一次 foreground。 */
    fun onForeground() {
        if (!allowed()) return
        val now = System.currentTimeMillis()
        // 每次回前台都把积压队列冲一次。断网、TLS 失败或进程被杀都可能让
        // 上一次 flush 落空，靠定时重试要等一分钟，用户切回来就该补上。
        // 被 30 秒去抖挡掉的回前台也不例外。
        if (now - lastForegroundAt < 30_000) {
            reportActive()
            scheduleFlush(0)
            return
        }
        val coldStart = lastForegroundAt == 0L
        lastForegroundAt = now
        if (!coldStart) track(Type.FOREGROUND)
        reportActive()
        scheduleFlush(0)
    }

    fun track(type: Type) {
        if (!allowed()) return
        val event = buildEvent(type) ?: return
        append(event.toString())
        scheduleFlush(2_000)
    }

    /**
     * 关闭授权：补发一条 `opt_out`（只发一次、不落盘），然后删掉本机队列与死信。
     * 之后连队列文件都不会再创建。
     */
    fun optOut() {
        if (!installed || BASE_URL.contains("example.com")) {
            clear()
            return
        }
        val farewell = buildEvent(Type.OPT_OUT)
        clear()
        if (farewell == null) return
        scope.launch { runCatching { post(listOf(farewell)) } }
    }

    /** 只删文件，不动授权状态。 */
    fun clear() {
        if (!installed) return
        runCatching {
            queueFile.delete()
            deadFile.delete()
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun allowed(): Boolean =
        installed &&
            AppSettings.usageStatsDecided.value &&
            AppSettings.usageStats.value &&
            !BASE_URL.contains("example.com")

    private fun buildEvent(type: Type): JSONObject? = runCatching {
        JSONObject().apply {
            put("sid", installId())
            put("type", type.wire)
            put("ts", System.currentTimeMillis())
            put("day", today())
            put("ver", com.music.bitchord.BuildConfig.VERSION_NAME)
            put("code", com.music.bitchord.BuildConfig.VERSION_CODE)
            put("brand", android.os.Build.BRAND.orEmpty().lowercase(Locale.ROOT).take(24))
            put("model", android.os.Build.MODEL.orEmpty().take(32))
            put("os", android.os.Build.VERSION.RELEASE.orEmpty().take(8))
            put("api", android.os.Build.VERSION.SDK_INT)
        }
    }.getOrNull()

    private fun scheduleFlush(afterMs: Long) {
        if (!allowed()) return
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            flush()
        }
    }

    private fun append(line: String) {
        synchronized(this) {
            runCatching {
                dir.mkdirs()
                if (queueFile.length() > MAX_QUEUE_BYTES) trimQueue()
                queueFile.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "append failed: ${it.message}") }
        }
    }

    private fun trimQueue() {
        val lines = queueFile.readLines().takeLast(MAX_QUEUE)
        queueFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun flush() {
        if (!allowed()) return
        if (!flushing.compareAndSet(false, true)) return
        try {
            repeat(MAX_BATCHES_PER_FLUSH) {
                val all = readQueue()
                if (all.isEmpty()) return@repeat
                val batch = all.take(MAX_BATCH)
                if (send(batch) == Outcome.RETRY) {
                    bumpAttempts(batch)
                    scheduleFlush(backoffMs())
                    return@repeat
                }
            }
        } finally {
            flushing.set(false)
        }
    }

    private enum class Outcome { DONE, RETRY }

    private fun send(batch: List<JSONObject>): Outcome {
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("events", JSONArray(batch.map { contractOnly(it) }))
        }.toString()

        val request = Request.Builder()
            .url(BASE_URL + PATH)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonType))
            .build()

        // 网络/TLS/证书钉扎失败时这里吞掉异常，服务端日志里就一条 POST 都没有。
        // 留一条 logcat 线索，排查「装了新版本却查不到日活」时能直接定位。
        val response = runCatching { client.newCall(request).execute() }
            .onFailure { Log.w(TAG, "post failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
            ?: return Outcome.RETRY
        response.use { r ->
            if (r.isSuccessful) {
                val rejected = parseRejects(r.body?.string().orEmpty())
                if (rejected == null) {
                    // 回执解析不了：一条都不删，下轮重发（服务端按 sid+ts 幂等）。
                    Log.w(TAG, "unreadable receipt; keeping ${batch.size} events")
                    return Outcome.RETRY
                }
                val dead = batch.filterIndexed { index, _ -> index in rejected }
                if (dead.isNotEmpty()) writeDead(dead, "server reject")
                markActiveConfirmed(batch, rejected)
                dequeueBatch(batch, rejected)
                return Outcome.DONE
            }
            return when {
                r.code == 429 || r.code >= 500 || r.code == 413 -> Outcome.RETRY
                r.code in 400..499 -> {
                    // 格式类错误，重发只会一直撞墙。
                    writeDead(batch, "http ${r.code}")
                    dequeueBatch(batch, batch.indices.associateWith { "http ${r.code}" })
                    Outcome.DONE
                }
                else -> Outcome.RETRY
            }
        }
    }

    /**
     * 下标 → 拒绝原因。回执坏掉（不是 JSON）返回 null。
     *
     * 只认 `rejects[].i`：`accepted` / `duplicated` 的计数不参与判定——没进
     * `rejects` 的一律当收到，这正是服务端的语义。
     */
    private fun parseRejects(text: String): Map<Int, String>? = runCatching {
        val obj = JSONObject(text)
        val rejects = obj.optJSONArray("rejects") ?: JSONArray()
        val out = mutableMapOf<Int, String>()
        for (i in 0 until rejects.length()) {
            val item = rejects.optJSONObject(i) ?: continue
            val index = item.optInt("i", -1)
            if (index >= 0) out[index] = item.optString("reason").orEmpty()
        }
        out
    }.getOrNull()

    /** 按内容出队：只删「这批里、且没被拒」的那些。 */
    private fun dequeueBatch(batch: List<JSONObject>, rejected: Map<Int, String>) {
        val acceptedKeys = batch.filterIndexed { index, _ -> index !in rejected }
            .map { it.key() }
            .toSet()
        if (acceptedKeys.isEmpty()) return
        writeQueue(readQueue().filterNot { it.key() in acceptedKeys })
    }

    private fun JSONObject.key(): String = optString("sid") + "|" + optLong("ts")

    private fun bumpAttempts(batch: List<JSONObject>) {
        val keys = batch.map { it.key() }.toSet()
        val dead = mutableListOf<JSONObject>()
        val rest = mutableListOf<JSONObject>()
        for (event in readQueue()) {
            if (event.key() !in keys) {
                rest += event
                continue
            }
            val attempts = event.optInt("att", 0) + 1
            if (attempts >= MAX_ATTEMPTS) {
                dead += event.put("att", attempts)
            } else {
                rest += event.put("att", attempts)
            }
        }
        if (dead.isNotEmpty()) writeDead(dead, "gave up after $MAX_ATTEMPTS attempts")
        writeQueue(rest)
    }

    private fun writeDead(batch: List<JSONObject>, why: String) {
        runCatching {
            deadFile.appendText(batch.joinToString("") { it.put("why", why).toString() + "\n" })
            deadFile.writeText(
                deadFile.readLines().takeLast(MAX_DEAD).joinToString("\n", postfix = "\n"),
            )
        }
    }

    private fun readQueue(): List<JSONObject> = synchronized(this) {
        if (!queueFile.exists()) return emptyList()
        val out = mutableListOf<JSONObject>()
        queueFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { out += JSONObject(line) }
                .onFailure { Log.w(TAG, "bad queue line dropped: ${it.message}") }
        }
        out
    }

    private fun writeQueue(items: List<JSONObject>) = synchronized(this) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "queue.tmp")
            tmp.writeText(items.joinToString("") { it.toString() + "\n" })
            if (!tmp.renameTo(queueFile)) {
                queueFile.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "write queue failed: ${it.message}") }
    }

    /** 只保留契约字段：`att` / `why` 这类本地标记发出去会让整条被拒。 */
    private fun contractOnly(event: JSONObject): JSONObject = JSONObject().also { out ->
        CONTRACT_FIELDS.forEach { field -> if (event.has(field)) out.put(field, event.get(field)) }
    }

    private fun post(batch: List<JSONObject>) {
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("events", JSONArray(batch.map(::contractOnly)))
        }.toString()
        val request = Request.Builder()
            .url(BASE_URL + PATH)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonType))
            .build()
        runCatching { client.newCall(request).execute()?.close() }
    }

    private fun backoffMs(): Long = 60_000L

    private fun today(): String {
        val format = SimpleDateFormat("yyyyMMdd", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT+0$TZ_OFFSET_HOURS:00")
        return format.format(Date())
    }

    /**
     * 每天产出一条 active，直到服务端确认收到为止。
     *
     * 两个极端都要避开：标记写太早（刚入队就写）会在请求丢失后永久漏报，
     * 日活一直是 0；完全不标记（每次冷启动都发）会让「今日新增」重复累加。
     * 所以落盘标记只在收到成功回执后写（见 [markActiveConfirmed]），
     * 进程内标记只负责同一次启动不重复。标记换用新 key，避开旧版留下的脏值。
     */
    private fun reportActive() {
        if (!allowed()) return
        val day = today()
        if (activeSentDay == day) return
        if (lastActiveDay() == day) return
        track(Type.ACTIVE)
        activeSentDay = day
    }

    /**
     * 只有这一批里确实有一条 active 被服务端收下（没进 rejects）时，
     * 才把当天记成已确认。`duplicated` 不进 rejects，也算收下。
     */
    private fun markActiveConfirmed(batch: List<JSONObject>, rejected: Map<Int, String>) {
        batch.forEachIndexed { index, event ->
            if (index !in rejected && event.optString("type") == Type.ACTIVE.wire) {
                val day = event.optString("day")
                if (day.isNotBlank()) setLastActiveDay(day)
            }
        }
    }

    private fun lastActiveDay(): String? =
        prefs()?.getString(KEY_CONFIRMED_ACTIVE_DAY, null)

    private fun setLastActiveDay(day: String) {
        prefs()?.edit()?.putString(KEY_CONFIRMED_ACTIVE_DAY, day)?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences("usage_stats", Context.MODE_PRIVATE)

    /** 安装 id：随机 UUID，只存本机；卸载重装即换——「安装」口径本来如此。 */
    private fun installId(): String {
        val existing = prefs()?.getString("install_id", null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs()?.edit()?.putString("install_id", id)?.apply()
        return id
    }
}
