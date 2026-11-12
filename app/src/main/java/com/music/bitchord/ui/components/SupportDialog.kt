package com.music.bitchord.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R

/**
 * 赞助页。
 *
 * 全屏黑底，图片居中。**点一下放大**（1 → 1.9 倍，带动画），放大后可以拖动看细节；
 * **长按保存**到相册（`Pictures/YiJi`）。
 *
 * 不用第三方图片查看库：整个页面只有一张固定的资源图，zoom + 存图两件事各三十行，
 * 引一个库反而要处理它的主题、手势冲突和 R8 规则。
 */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var zoomed by remember { mutableStateOf(false) }
    // 只做「点一下放大 / 再点还原」：两指缩放和拖动会和点按手势抢事件，
    // 而这里要处理的只有一张二维码，能看清就够了。
    val scale by animateFloatAsState(if (zoomed) 1.9f else 1f, label = "supportZoom")

    Dialog(
        onDismissRequest = onDismiss,
        // 默认宽度是 280dp 的对话框；这张图要铺满屏。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .safeDrawingPadding(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { zoomed = !zoomed },
                                onLongPress = { saveSupportImage(context) },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.support_qr),
                        contentDescription = "赞助",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
                Text(
                    text = "点击图片放大，再点还原",
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(top = 12.dp),
                )
                // 长按存图不是能被看见的操作，补两个明确按钮——
                // 触控目标 48dp，读屏也能念出来。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { saveSupportImage(context) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("保存到相册", color = Color.White, fontSize = 16.sp)
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp).padding(start = 8.dp),
                    ) {
                        Text("关闭", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * 存到相册。Android 10 起走 MediaStore 的 `RELATIVE_PATH`，不需要任何权限；
 * 更老的系统（本机最低 26）退回 `insertImage`，失败就把图放进应用自己的图片目录并提示路径。
 */
private fun saveSupportImage(context: Context) {
    val bitmap = runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.support_qr)
    }.getOrNull() ?: run {
        Toast.makeText(context, "保存失败：图片读取不到", Toast.LENGTH_SHORT).show()
        return
    }
    val name = "yiji-support-${System.currentTimeMillis()}.jpg"

    val ok = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/YiJi",
                )
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
        } ?: error("open failed")
        true
    }.getOrDefault(false)

    if (ok) {
        Toast.makeText(context, "已保存到相册（Pictures/YiJi）", Toast.LENGTH_SHORT).show()
        return
    }

    // 老系统没写外部存储权限时的退路：应用自己的目录，文件管理器里也能看到。
    val fallback = runCatching {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        dir.mkdirs()
        val file = java.io.File(dir, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        file.absolutePath
    }.getOrNull()

    Toast.makeText(
        context,
        if (fallback != null) "已保存到 $fallback" else "保存失败",
        Toast.LENGTH_LONG,
    ).show()
}
