package com.music.bitchord.data.lyrico

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 装进来的 Lyrico 歌词插件。
 *
 * 一条记录就是一个插件：`manifest.json` 里那几样身份信息（名字、版本、能力）留在
 * 偏好里，JS 正文单独放在 `filesDir/lyrico/<id>.js` —— 一个插件脚本几十到几百 KB，
 * 塞进 SharedPreferences 会把整份偏好读进内存，而它几乎不需要被读。
 *
 * ### 导入形态
 *
 * Lyrico 官方把插件打包成「目录里一个 manifest.json + 一个 source.js」。这里只
 * 要求那个 **JS 文件**：manifest 里的配置项、图标、多语言在「匹配歌词」这一件事
 * 上用不上，而能力是靠探测有没有 `getLyrics` 判断的，不靠声明 —— 声明了却没实现
 * 的插件，跑起来只会报错，探测则一开始就把它拒掉。
 */
data class LyricoPlugin(
    val id: String,
    val name: String,
    val versionName: String,
    /** 有没有实现 getLyrics。没有的插件不参与匹配，列出来也要说明。 */
    val doesLyrics: Boolean,
    val enabled: Boolean,
) {
    /** JS 正文的落盘名。 */
    val fileName: String get() = "$id.js"
}

object LyricoPlugins {

    private const val TAG = "BitChord"
    private const val PREF_NAME = "bitchord_lyrico_plugins"
    private const val KEY_PLUGINS = "plugins_json"

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File

    private val _plugins = MutableStateFlow<List<LyricoPlugin>>(emptyList())
    val plugins: StateFlow<List<LyricoPlugin>> = _plugins.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        dir = File(context.applicationContext.filesDir, "lyrico").apply { mkdirs() }
        _plugins.value = load()
    }

    /**
     * 存下并启用一个插件脚本。
     *
     * [suggestedName] 是文件名或 manifest 里的名字；id 由名字生成，同名重复导入算
     * 更新那一条而不是多出一条。
     */
    suspend fun add(context: Context, suggestedName: String, js: String): Result<LyricoPlugin> =
        withContext(Dispatchers.IO) {
            init(context)
            if (js.isBlank()) return@withContext Result.failure(IllegalArgumentException("这个文件是空的"))
            val name = suggestedName.substringBeforeLast('.')
                .ifBlank { "插件" }
            val id = idOf(name)
            runCatching {
                File(dir, "$id.js").writeText(js)
            }.getOrElse { return@withContext Result.failure(it) }
            // 能不能做歌词要跑一遍才知道，而不是看它自己怎么说 —— 探测用的是真脚本。
            val doesLyrics = probe(id, js)
            val plugin = LyricoPlugin(
                id = id,
                name = name,
                versionName = versionOf(js),
                doesLyrics = doesLyrics,
                enabled = true,
            )
            val updated = _plugins.value.filterNot { it.id == id } + plugin
            _plugins.value = updated
            persist(updated)
            if (!doesLyrics) {
                Log.d(TAG, "plugin ${plugin.name} has no getLyrics; it will not be asked for lyrics")
            }
            Result.success(plugin)
        }

    fun remove(context: Context, id: String) {
        init(context)
        LyricoRuntime.unload(id)
        runCatching { File(dir, "$id.js").delete() }
        val updated = _plugins.value.filterNot { it.id == id }
        _plugins.value = updated
        persist(updated)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        init(context)
        val updated = _plugins.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _plugins.value = updated
        persist(updated)
        if (!enabled) LyricoRuntime.unload(id)
    }

    /** 一个插件的 JS 正文（引擎按它建 VM）。 */
    fun scriptOf(context: Context, id: String): String? {
        init(context)
        return runCatching { File(dir, "$id.js").readText() }.getOrNull()
    }

    /**
     * 让每个启用且真做歌词的插件都答一次，把结果并成一个列表。
     *
     * 并发限成 3：这些插件在自己的 VM 线程上做同步 HTTP，几十个一起放出去只会互相
     * 抢带宽，还让一次批量匹配变成一次对若干第三方站点的洪水。
     */
    suspend fun lyrics(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<LyricoHit> = withContext(Dispatchers.IO) {
        init(context)
        val usable = _plugins.value.filter { it.enabled && it.doesLyrics }
        if (usable.isEmpty()) return@withContext emptyList()
        val gate = Semaphore(MAX_PARALLEL_PLUGINS)
        usable.mapNotNull { plugin ->
            val js = scriptOf(context, plugin.id) ?: return@mapNotNull null
            gate.withPermit {
                val found = LyricoRuntime.lyrics(
                    pluginId = plugin.id,
                    jsCode = js,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    config = JSONObject(),
                ).getOrElse { failure ->
                    Log.d(TAG, "plugin ${plugin.name} threw: ${failure.message}")
                    emptyList()
                }
                found.map { LyricoHit(plugin.name, it) }
            }
        }.flatten()
    }

    /** 哪个插件给的 + 它给的东西。 */
    data class LyricoHit(val pluginName: String, val result: LyricPluginResult)

    private const val MAX_PARALLEL_PLUGINS = 3

    /** 能不能做歌词交给宿主自己探（见 [LyricoRuntime.canDoLyrics]）。 */
    private suspend fun probe(id: String, js: String): Boolean = LyricoRuntime.canDoLyrics(id, js)

    private fun idOf(name: String): String =
        name.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
            .ifBlank { "plugin" }

    /** manifest 常内联在脚本头部；没有就当 0.0.0。 */
    private fun versionOf(js: String): String =
        Regex("""versionName\s*[:=]\s*["']([^"']+)["']""").find(js)?.groupValues?.get(1).orEmpty()
            .ifBlank { "0.0.0" }

    private fun load(): List<LyricoPlugin> {
        if (!this::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_PLUGINS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LyricoPlugin(
                    id = id,
                    name = item.optString("name", id),
                    versionName = item.optString("versionName", "0.0.0"),
                    doesLyrics = item.optBoolean("doesLyrics", true),
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<LyricoPlugin>) {        if (!this::prefs.isInitialized) return
        val array = JSONArray()
        list.forEach { plugin ->
            array.put(
                JSONObject()
                    .put("id", plugin.id)
                    .put("name", plugin.name)
                    .put("versionName", plugin.versionName)
                    .put("doesLyrics", plugin.doesLyrics)
                    .put("enabled", plugin.enabled),
            )
        }
        prefs.edit().putString(KEY_PLUGINS, array.toString()).apply()
    }
}
