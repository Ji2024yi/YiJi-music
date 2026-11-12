package com.music.bitchord.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯本机的崩溃诊断日志。
 *
 * 不联网、不接第三方 SDK、不读 IMEI / Android ID、不上传任何东西。只在应用私有
 * 目录留几份文本，由用户在「设置 → 导出数据」里主动导出或清除。隐私政策第三节
 * 列出它、第七节说明怎么删。
 *
 * 记录内容刻意只留排查所需的机器信息：时间、版本、设备型号、Android 版本、线程、
 * 异常类型与堆栈。消息和堆栈会先过 [redact]，把 URL、邮箱、长数字串（手机号、
 * token、歌单 id 等）替换成占位符，避免把账号或听歌内容写进文件。
 */
object CrashLog {

    /** 只留最近几次，避免反复崩溃把私有目录写满。 */
    private const val MAX_FILES = 5
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_STACK_LINES = 60

    private val URL = Regex("""https?://[^\s"'<>]+""")
    private val EMAIL = Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}""")
    private val LONG_NUMBER = Regex("""\b\d{6,}\b""")

    /** 尽量早调用；重复调用是安全的。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app, previous))
    }

    private class CrashHandler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            // 进程已经要死了，不能起协程；这里所有 IO 都必须同步且绝不抛新异常。
            try {
                write(app, thread, error)
            } catch (_: Throwable) {
                // 日志失败不能盖掉真正的崩溃。
            }
            // 必须交回系统默认处理，否则进程会静默卡住而不是正常退出。
            previous?.uncaughtException(thread, error)
        }
    }

    /** 本机保存的记录，新的在前。 */
    fun entries(app: Context): List<File> =
        try {
            dir(app).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.name }
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

    /** 导出用的合并文本；没有记录时是空串。 */
    fun readAll(app: Context): String =
        entries(app).joinToString("\n\n") { file ->
            runCatching { file.readText() }.getOrDefault("")
        }

    fun clear(app: Context) {
        entries(app).forEach { runCatching { it.delete() } }
    }

    private fun dir(app: Context): File =
        File(app.filesDir, "crash").apply { if (!exists()) mkdirs() }

    private fun write(app: Context, thread: Thread, error: Throwable) {
        val directory = dir(app)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val name = "crash-$stamp.txt"
        val text = buildString {
            append("---- 崩溃诊断记录 ----\n")
            append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("版本: ").append(appVersion(app)).append('\n')
            append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程: ").append(thread.name).append('\n')
            appendCause(this, error, "异常")
            append("\n堆栈 (最多 ").append(MAX_STACK_LINES).append(" 行):\n")
            error.stackTrace.take(MAX_STACK_LINES).forEach { frame ->
                append("  at ").append(redact(frame.toString())).append('\n')
            }
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append('\n')
                appendCause(this, cause, "Caused by")
                cause.stackTrace.take(12).forEach { frame ->
                    append("  at ").append(redact(frame.toString())).append('\n')
                }
                cause = cause.cause
                depth++
            }
        }
        val cropped = if (text.length > MAX_BYTES) text.take(MAX_BYTES) else text
        File(directory, name).writeText(cropped)

        // 超出上限就删最老的，保持目录有界。
        val all = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        all.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun appendCause(out: StringBuilder, error: Throwable, label: String) {
        out.append(label).append(": ").append(error.javaClass.name)
        error.message?.let { out.append(": ").append(redact(it.lineSequence().first())) }
        out.append('\n')
    }

    @Suppress("DEPRECATION")
    private fun appVersion(app: Context): String = try {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (_: Throwable) {
        "unknown"
    }

    /** URL / 邮箱 / 长数字串一律替换掉，日志里不留地址、账号与歌单 id。 */
    internal fun redact(text: String): String =
        LONG_NUMBER.replace(EMAIL.replace(URL.replace(text, "<url>"), "<email>"), "<number>")
}
