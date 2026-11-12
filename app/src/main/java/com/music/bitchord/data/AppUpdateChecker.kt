package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 更新提示（只提示，不下载、不安装）。
 *
 * 安全边界——改动前请先读完这几条：
 *  - 只 GET 一个固定的 GitHub API 地址，不带任何账号、设备或播放数据；
 *  - 只解析 tag_name / html_url / name / body 四个字符串，body 只当纯文本截断展示；
 *  - 不读 assets、不下载 APK、不申请安装权限、不动态加载任何远程内容；
 *  - 唯一的对外动作是 ACTION_VIEW 打开浏览器，URL 必须先过 [isGithubReleaseUrl] 白名单；
 *  - 不 pin GitHub 证书（GitHub 轮换频繁），走系统 CA；
 *  - 用独立 OkHttpClient，绝不复用带第三方 Cookie / 拦截器的全局 Http.client。
 */
object AppUpdateChecker {

    /** 换成自己的仓库时，只改这两行。 */
    const val REPO_OWNER = "Ji2024yi"
    const val REPO_NAME = "YiJi-music"

    private const val API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private const val PREFS = "bitchord_update"
    private const val KEY_IGNORED_REV = "ignored_rev"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val MAX_NOTES = 400

    data class UpdateInfo(
        val rev: Int,
        val tag: String,
        val releaseUrl: String,
        val title: String,
        val notes: String,
    )

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 设置页手动检查的结果；有新版本时弹窗由 UpdateNoticeHost 统一负责。 */
    enum class CheckResult { AVAILABLE, UP_TO_DATE, FAILED }

    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@withContext
        // 冷启动的自动检查尊重「忽略此版本」。
        checkNow(context, respectIgnored = true)
    }

    /** 设置页点「检查更新」：跳过 12 小时节流，也不沿用旧的忽略决定。 */
    suspend fun checkNow(context: Context, respectIgnored: Boolean = false): CheckResult =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            val info = runCatching { fetch() }.getOrNull()
                ?: return@withContext CheckResult.FAILED
            // 防降级：只有 tag 数字严格大于本包内置序号才提示。
            if (info.rev <= BuildConfig.RELEASE_REV) return@withContext CheckResult.UP_TO_DATE
            if (respectIgnored && info.rev <= prefs.getInt(KEY_IGNORED_REV, 0)) {
                return@withContext CheckResult.UP_TO_DATE
            }
            _available.value = info
            CheckResult.AVAILABLE
        }

    private fun fetch(): UpdateInfo? {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "YiJi/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) return null
            val tag = obj.optString("tag_name").trim()
            val rev = parseReleaseRev(tag) ?: return null
            val url = obj.optString("html_url")
            if (!isGithubReleaseUrl(url)) return null
            return UpdateInfo(
                rev = rev,
                tag = tag,
                releaseUrl = url,
                title = obj.optString("name").take(120),
                notes = obj.optString("body").replace(Regex("[\\r\\n]+"), " ").take(MAX_NOTES),
            )
        }
    }

    /** Release tag 只认 vNN / NN，避免把语义版本或随机字符串当成可比较数字。 */
    internal fun parseReleaseRev(tag: String): Int? =
        Regex("^v?(\\d{1,9})$").matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** 只允许 https://github.com/<owner>/<repo>/releases/... */
    internal fun isGithubReleaseUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" &&
            uri.host == "github.com" &&
            (uri.path ?: "").startsWith(
                "/$REPO_OWNER/$REPO_NAME/releases/",
                ignoreCase = true,
            )
    }.getOrDefault(false)

    fun ignore(context: Context, rev: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_IGNORED_REV, rev).apply()
        _available.value = null
    }

    fun dismiss() {
        _available.value = null
    }

    /** 打开浏览器前再校验一次。绝不下载、安装或执行远程内容。 */
    fun openInBrowser(context: Context, info: UpdateInfo): Boolean {
        if (!isGithubReleaseUrl(info.releaseUrl)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
