package com.music.bitchord.data.account

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 本地账户头像的存放处。
 *
 * 用户从 Photo Picker 选中的图会被复制进 App 私有目录，持久化的是复制后的
 * `file://` 路径，不是 picker 返回的 content URI——后者只是临时授权，进程重启
 * 或过一段时间就失效，顶栏头像会变空白。
 *
 * 每次换图都写成新文件名：旧图在新图确实落地之前保持不动，任何一步失败都
 * 不会留下「路径还在、文件没了」的空窗；文件名变化也会让存下来的路径变化，
 * 设置页和顶栏才会真正重新加载，而不是继续吃 Coil 的旧缓存。
 */
object LocalAvatarStore {

    private const val DIR_NAME = "account"
    private const val FILE_NAME = "topbar-avatar"

    private fun isAvatarFile(name: String): Boolean =
        name == "$FILE_NAME.dat" ||
            (name.startsWith("$FILE_NAME-") && name.endsWith(".dat"))

    /** 复制成功返回可直接交给 Coil 的 `file://` 字符串；失败返回 null（调用方保持原值）。 */
    fun import(context: Context, source: Uri): String? = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) return null

        val staging = File(dir, "$FILE_NAME.incoming")
        context.contentResolver.openInputStream(source)?.use { input ->
            staging.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        if (staging.length() == 0L) {
            staging.delete()
            return null
        }

        val target = File(dir, "$FILE_NAME-${System.currentTimeMillis()}.dat")
        if (!staging.renameTo(target)) {
            staging.delete()
            return null
        }
        // 新图落地之后才清理旧图；上面任何一步失败，旧头像都原封不动。
        dir.listFiles { _, name -> isAvatarFile(name) && name != target.name }
            ?.forEach { it.delete() }
        Uri.fromFile(target).toString()
    }.getOrNull()

    /**
     * 偏好里的路径已经失效时，从私有目录里找回最近一次成功落地的头像。
     * 冷启动调一次即可：备份恢复、版本升级、旧版残留的临时 URI 都能自愈，
     * 不用用户重新选一次图。
     */
    fun recoverPath(context: Context): String? = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        val best = dir.listFiles { _, name -> isAvatarFile(name) }
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
        best?.let { Uri.fromFile(it).toString() }
    }.getOrNull()

    /** 清除头像文件。偏好由调用方一并清空，避免顶栏指向不存在的文件。 */
    fun clear(context: Context) {
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            dir.listFiles { _, name -> isAvatarFile(name) || name == "$FILE_NAME.incoming" }
                ?.forEach { it.delete() }
        }
    }

    /** 偏好里存的路径是否还能用；文件没了就当作没设过头像。 */
    fun exists(path: String): Boolean = runCatching {
        path.isNotBlank() && File(Uri.parse(path).path ?: return false).isFile
    }.getOrDefault(false)
}
