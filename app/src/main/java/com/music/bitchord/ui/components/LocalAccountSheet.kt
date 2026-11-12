package com.music.bitchord.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.data.account.LocalAvatarStore
import com.music.bitchord.data.settings.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 本地假账户的头像预览与编辑器。
 *
 * [LocalAvatarPreview] 画的圆和顶栏 `TopBarAccountButton` 用的是同一套尺寸与
 * 圆角，所以设置页里看到的即是真的效果；[LocalAccountSheet] 同时给出 28dp
 * （顶栏实际直径）和一张大图，方便判断选出去的图在小圆里认不认得出。
 */

/** 顶栏头像的实际直径，见 FrostedTopBar.AVATAR_SIZE。 */
val TOPBAR_AVATAR_SIZE = 28.dp

@Composable
fun LocalAvatarPreview(path: String, size: Dp, modifier: Modifier = Modifier) {
    // 文件还在但解码失败（图片损坏、缓存被清）时也要有占位，否则 AsyncImage
    // 会把整个圆画成空白，看起来就像头像凭空消失。
    var failed by remember(path) { mutableStateOf(false) }
    val usable = path.isNotBlank() && !failed && LocalAvatarStore.exists(path)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (usable) {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
                onError = { failed = true },
            )
        } else {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size.value * 0.64f).dp),
            )
        }
    }
}

@Composable
fun LocalAccountSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name by AppSettings.localAccountName.collectAsStateWithLifecycle()
    val path by AppSettings.localAvatarPath.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 复制进私有目录再存路径：picker 给的 content URI 只是临时授权，
            // 直接存下来过一阵顶栏就会变空白。
            val stored = LocalAvatarStore.import(context, uri)
            if (stored != null) AppSettings.setLocalAvatarPath(stored)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "本地账户",
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalAvatarPreview(path = path, size = TOPBAR_AVATAR_SIZE)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "顶栏里的实际大小",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(18.dp))
                    LocalAvatarPreview(path = path, size = 72.dp)
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { AppSettings.setLocalAccountName(it) },
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }) { Text(if (path.isBlank()) "选头像" else "换一张") }

                    if (path.isNotBlank()) {
                        TextButton(onClick = {
                            LocalAvatarStore.clear(context)
                            AppSettings.setLocalAvatarPath("")
                        }) { Text("清除") }
                    }

                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}
