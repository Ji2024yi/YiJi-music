package com.music.bitchord.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker

/**
 * 冷启动查一次 GitHub Release，有新版本就弹一次。
 *
 * 点「查看更新」只跳浏览器打开发布页；本应用不下载 APK、不申请安装权限、
 * 不替用户安装。点对话框外面 = 稍后再说，点「忽略此版本」= 这个 tag 不再提示。
 */
@Composable
fun UpdateNoticeHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppUpdateChecker.check(context) }

    val info by AppUpdateChecker.available.collectAsStateWithLifecycle()
    val current = info ?: return

    AlertDialog(
        onDismissRequest = { AppUpdateChecker.dismiss() },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(
                buildString {
                    append(stringResource(R.string.update_notice_body, current.rev))
                    if (current.notes.isNotBlank()) {
                        append("\n\n")
                        append(current.notes)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                AppUpdateChecker.openInBrowser(context, current)
                AppUpdateChecker.dismiss()
            }) { Text(stringResource(R.string.update_notice_view)) }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdateChecker.ignore(context, current.rev) }) {
                Text(stringResource(R.string.update_notice_ignore))
            }
        },
    )
}
