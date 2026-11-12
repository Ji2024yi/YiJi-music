package com.music.bitchord.auth

private val WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

/**
 * Used only if the platform hands us a user agent we don't recognise, so that a
 * blank login page can never be the outcome of the string being unparseable.
 */
private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/**
 * 剥掉 WebView 用户串里那两个自曝身份的标志。
 *
 * `; wv` 和 `Version/4.0` 这一对被服务端读成 "Android Browser 4.0"——多年前就
 * 不再支持的版本——于是返回一个壳页面，登录表单根本不渲染。部分设备上会
 * 打一行 `WebRTC is not supported on Android Browser 4.0` 然后什么都不画。
 *
 * 改平台串而不是塞一个固定值，是为了让真实的机型和真实的 Chrome 版本留在里面，
 * 不至于下次服务端抬高门槛时，这里还在自称两年前的浏览器。
 *
 * 原来住在 `DiscordLoginScreen.kt` 里，和 Discord 的登录屏一起被删；但
 * [SpotifyLoginScreen] 也在用它，所以单独留在这个文件。
 */
internal fun browserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}
