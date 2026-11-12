package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrico.LyricoPlugin
import com.music.bitchord.data.lyrico.LyricoPlugins
import com.music.bitchord.ui.icons.SfIcons

/**
 * 歌词插件这一页。
 *
 * 添加只走**网址**一条路：把 Lyrico 的 `source.js` 放到你自己的服务器上（他本来就
 * 有一台 WebDAV），在这儿填那条直链就行。这么定有两个理由 —— 手机上翻存储挑文件
 * 需要一个系统文件选择器和一堆权限，而一个直链既能从电脑上推上去、也能直接从插件
 * 项目的 release 页粘过来；并且装了什么、来源在哪，他自己心里有数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricoPluginsSheet(
    onDismiss: () -> Unit,
    onAddFromUrl: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    busy: Boolean,
    status: String?,
) {
    val plugins by LyricoPlugins.plugins.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(SfIcons.Close, contentDescription = stringResource(R.string.close))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.lyric_plugins),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.lyric_plugin_url)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (url.isNotBlank() && !busy) onAddFromUrl(url.trim()) },
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (url.isNotBlank() && !busy) onAddFromUrl(url.trim()) },
                        enabled = url.isNotBlank() && !busy,
                    ) {
                        Text(
                            text = if (busy) {
                                stringResource(R.string.lyric_plugin_fetching)
                            } else {
                                stringResource(R.string.lyric_plugin_add)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn {
                items(plugins, key = { it.id }) { plugin ->
                    PluginRow(plugin, onRemove, onToggle)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (plugins.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.lyric_plugins_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
private fun PluginRow(
    plugin: LyricoPlugin,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(plugin.id, !plugin.enabled) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // 不做歌词的插件要讲明白：它会出现在列表里但不参与匹配，别让人以为
                // 装了就有结果。
                text = if (plugin.doesLyrics) {
                    plugin.versionName
                } else {
                    "${plugin.versionName} · ${stringResource(R.string.lyric_plugin_no_lyrics)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = plugin.enabled, onCheckedChange = { onToggle(plugin.id, it) })
        IconButton(onClick = { onRemove(plugin.id) }) {
            Icon(SfIcons.Trash, contentDescription = stringResource(R.string.delete))
        }
    }
}
