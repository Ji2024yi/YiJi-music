package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.netease.NeteaseApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 登录页的两个入口。 */
private enum class LoginMode { Qr, Phone }

/** 二维码那一侧的可见状态，第四个（成功）直接走回调、不留在这个屏上。 */
private enum class QrStatus { Fetching, Waiting, Scanned, Failed }

/**
 * 网易云登录：扫码 / 手机号验证码。
 *
 * 扫码走开源社区那套 `unikey` + 轮询；手机号走 `/api/sms/captcha/sent` +
 * `/api/login/cellphone`。两条路过：扫码要求请求里带稳定的 `_ntes_nuid`，
 * 否则网易云回 `-460`「检测到您的网络环境存在风险」——这就是他看到的"环境异常"。
 * 那条设备标识现在由 `NeteaseAuth.deviceId` 提供，两条路一起受益。
 *
 * 为什么还留扫码：它不需要把手机号交给任何一方，能扫的时候最快。手机号是兜底，
 * 也是他自己要求的。
 */
@Composable
fun NeteaseLoginPanel(
    onCaptured: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(LoginMode.Qr) }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeTab(
                label = stringResource(R.string.netease_tab_qr),
                selected = mode == LoginMode.Qr,
                onClick = { mode = LoginMode.Qr },
            )
            Spacer(Modifier.width(8.dp))
            ModeTab(
                label = stringResource(R.string.netease_tab_phone),
                selected = mode == LoginMode.Phone,
                onClick = { mode = LoginMode.Phone },
            )
        }
        when (mode) {
            LoginMode.Qr -> QrSection(onCaptured, Modifier.weight(1f))
            LoginMode.Phone -> PhoneSection(onCaptured, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !selected) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ── 扫码 ─────────────────────────────────────────────────────────────

@Composable
private fun QrSection(onCaptured: (String) -> Unit, modifier: Modifier) {
    var token by remember { mutableIntStateOf(0) }
    var key by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(QrStatus.Fetching) }
    // 失败要说清是哪一种。早期实现两种失败都只显示"二维码获取失败"，而
    // 用户看到的恰恰是"已经授权了却说获取失败"，那句话把他引到了完全错误的方向。
    var failure by remember { mutableStateOf<Int?>(null) }
    val callback by rememberUpdatedState(onCaptured)

    // 取 key。过期和手动刷新都只是把这个 token 加一。
    LaunchedEffect(token) {
        key = null
        status = QrStatus.Fetching
        val fresh = runCatching { NeteaseApi.qrKey() }.getOrNull()
        if (fresh.isNullOrBlank()) {
            Log.w("BitChord", "netease qr: no unikey")
            failure = R.string.netease_qr_key_failed
            status = QrStatus.Failed
            return@LaunchedEffect
        }
        key = fresh
        status = QrStatus.Waiting
    }

    // 轮询。key 变了就重开一轮；拿到 cookie 或者过期都会结束这一轮。
    LaunchedEffect(key) {
        val current = key ?: return@LaunchedEffect
        while (isActive) {
            delay(QR_POLL_MS)
            val answer = runCatching { NeteaseApi.qrCheck(current) }.getOrNull() ?: continue
            when {
                answer.granted -> {
                    val cookie = answer.cookie
                    if (cookie.isNullOrBlank()) {
                        // 803 却没有 cookie：服务端给了个空壳。当作失败，让他重扫。
                        // 之前这里只报"二维码获取失败"，而真正的原因是
                        Log.w("BitChord", "netease qr: granted without a cookie")
                        failure = R.string.netease_qr_granted_no_cookie
                        status = QrStatus.Failed
                    } else {
                        callback(cookie)
                    }
                    return@LaunchedEffect
                }
                answer.expired -> {
                    token++
                    return@LaunchedEffect
                }
                answer.scanned -> status = QrStatus.Scanned
                else -> status = QrStatus.Waiting
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when (status) {
                    QrStatus.Scanned -> R.string.netease_qr_scanned
                    QrStatus.Failed -> R.string.netease_qr_failed
                    else -> R.string.netease_qr_hint
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .padding(vertical = 20.dp)
                .size(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            val content = key
            if (content != null && status != QrStatus.Failed) {
                QrCode(content = NeteaseApi.qrContent(content), size = 220.dp)
            } else if (status == QrStatus.Fetching) {
                CircularProgressIndicator()
            }
        }
        if (status == QrStatus.Failed) {
            Text(
                text = failure?.let { stringResource(it) }
                    ?: stringResource(R.string.netease_qr_failed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (failure == R.string.netease_qr_key_failed) {
                Text(
                    text = stringResource(R.string.netease_qr_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        TextButton(onClick = { token++ }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.netease_qr_refresh))
        }
    }
}

// ── 手机号 + 验证码 ──────────────────────────────────────────────────

@Composable
private fun PhoneSection(onCaptured: (String) -> Unit, modifier: Modifier) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onCaptured)
    val sentText = stringResource(R.string.netease_captcha_sent)
    val failedText = stringResource(R.string.netease_captcha_failed)
    val invalidText = stringResource(R.string.netease_phone_invalid)
    val unknownText = stringResource(R.string.netease_login_failed)

    // 倒计时。用固定 key 的 effect 每秒减一，而不是把 cooldown 当 key——
    // 那样每减一次都会重启协程，最后变成一个自我复位的定时器。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            if (cooldown > 0) cooldown--
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter(Char::isDigit).take(11) },
            label = { Text(stringResource(R.string.netease_phone_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.netease_captcha_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    if (phone.length != 11) {
                        status = invalidText
                    } else {
                        scope.launch {
                            sending = true
                            status = ""
                            val result = runCatching { NeteaseApi.sendCaptcha(phone) }
                                .getOrNull()
                            sending = false
                            // 服务端的中文原话（"检测到您的网络环境存在风险"之类）
                            // 比一句"发送失败"有用，直接照它显示。
                            status = if (result?.ok == true) {
                                cooldown = RESEND_SECONDS
                                sentText
                            } else {
                                result?.message ?: failedText
                            }
                        }
                    }
                },
                enabled = !sending && cooldown == 0 && !signingIn,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (cooldown > 0) {
                        stringResource(R.string.netease_resend_in, cooldown)
                    } else {
                        stringResource(R.string.netease_send_captcha)
                    },
                )
            }
            Button(
                onClick = {
                    if (phone.length != 11 || code.length < 4) {
                        status = invalidText
                    } else {
                        scope.launch {
                            signingIn = true
                            status = ""
                            val result = runCatching {
                                NeteaseApi.loginWithCaptcha(phone, code)
                            }.getOrNull()
                            signingIn = false
                            val cookie = result?.cookie
                            if (cookie != null) {
                                callback(cookie)
                            } else {
                                status = result?.message ?: unknownText
                            }
                        }
                    }
                },
                enabled = !signingIn && !sending && code.length >= 4 && phone.length == 11,
                modifier = Modifier.weight(1f),
            ) {
                if (signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.netease_do_login))
                }
            }
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.size(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 轮询间隔。服务端在扫码前一直回 801，问得再密也不会更早拿到结果。
 */
private const val QR_POLL_MS = 2_000L

/** 验证码重发冷却，跟页面上的习惯一致。 */
private const val RESEND_SECONDS = 60
