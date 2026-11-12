package com.music.bitchord.data.netease

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 网易云的登录凭据，网络层和界面读这里。
 *
 * 跟 [com.music.bitchord.data.webdav.WebDavAuth] 是同一个理由：真正的存储是加密的
 * （[com.music.bitchord.auth.AuthStore]），但那个类要 Context，而
 * [NeteaseApi] 跑在 IO 线程上、手上只有请求。所以由 AuthStore 在读写时把值发布到
 * 这里，网络层只读这份副本，两边不会各存一份。
 *
 * cookie 用 `@Volatile` 而不是 Flow：它被每次请求读取，订阅没有意义。昵称反过来
 * 是给设置页那一行看的，需要能重组成，所以是 Flow。
 */
object NeteaseAuth {

    /**
     * 浏览器里抓到的整串 cookie（`MUSIC_U`、`__csrf` 等），或验证码登录换回来的。
     *
     * 空表示没登录。没登录也能听免费曲目，所以空不是错误状态——[NeteaseApi]
     * 会退回一份匿名 cookie。
     */
    @Volatile
    var cookie: String? = null

    /**
     * 设备身份，32 位十六进制，装一次生成一次，之后一直用同一个。
     *
     * 这个字段是「环境异常」的根因。网易云把**请求里没有 `_ntes_nuid`**
     * 的客户端当成没有设备身份的异常来源，登录类接口统一回 `-460`「检测到您的网络
     * 环境存在风险」。同一台机器、同一个出口 IP，只把这一条 cookie 加上，手机号
     * 登录就从 `-460` 变成正常响应（`503 验证码错误`）——过，不是猜的。
     *
     * 它还必须**稳定**：每次请求随机一个等于每次都换一台设备，比没有更可疑。
     */
    @Volatile
    var deviceId: String = ""

    /**
     * 已登录账号的昵称，空表示还没拿到（刚登录完的几百毫秒里就是这样）或没登录。
     *
     * 只用于显示，不参与鉴权。存它是因为"已登录"这三个字对用户没有信息量，而昵称
     * 能让他确认登的是哪个号。
     */
    val nickname = MutableStateFlow("")

    /**
     * 登录账号的数字 uid，进程内缓存。
     *
     * `/api/likelist` 和 `/api/user/playlist` 都要带 `uid`，而这两个接口正是
     * 「我的喜欢 / 我的歌单」同步的入口。 每次都临时去问
     * `/api/nuser/account/get`，那个接口一旦被风控或晚到一步，uid 就是 null，
     * 两条同步一起静默失败 —— 真机上表现为「登录了但什么都没同步过来」。
     * 登录成功后 `account` 顺手把 uid 留在这里，后面的请求不必再赌一次网络。
     */
    @Volatile
    var userId: Long = 0L

    /**
     * cookie 还在、但服务端已经不认了。
     *
     * `isSignedIn` 只看 cookie 里有没有 `MUSIC_U`，而 `MUSIC_U` 是会过期的。
     * 过期之后 `/api/nuser/account/get` 回 `account: null`，需要登录的接口全部
     * 拿不到数据——但设置页显示的昵称是**上次登录时存下来的**，看起来一切正常。
     * 他连着两版反馈「我的喜欢和歌单没有同步过来」，很可能就是停在这个状态。
     *
     * 这里只做标记与显示，不自动清 cookie：免费曲目还能放，清掉反而更差。
     */
    val sessionExpired = MutableStateFlow(false)

    /**
     * 是否已登录。
     *
     * 与 [nickname] 分开是因为两者到达的时间不同：cookie 一到就算登录了，而昵称
     * 要等 [NeteaseApi.account] 返回。合成一个字段的话，设置页会在这段间隔里把
     * 一个刚登录成功的账号显示成"未登录"。
     */
    val signedIn = MutableStateFlow(false)

    val isSignedIn: Boolean get() = !cookie.isNullOrBlank()

    /**
     * 只留 `name=value` 形式的条目，去掉空名和空值。
     *
     * 从 WebView 抓来的串偶尔带尾随分号或空段，原样塞进请求头是一个语法上合法、
     * 语义上奇怪的头，服务端怎么理解都算它的自由。这里清一遍。
     */
    fun normalize(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .split(';')
            .map(String::trim)
            .filter { entry ->
                val name = entry.substringBefore('=', "").trim()
                val value = entry.substringAfter('=', "").trim()
                name.isNotEmpty() && value.isNotEmpty()
            }
        return cleaned.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * 这串 cookie 里有没有 `MUSIC_U` —— 有它才算真的登录了，别的都是访客标记。
     *
     * 跟 Google 那边按 cookie *名* 比对是同一课：`NMTID`、`_ntes_nuid` 这些访客
     * 标记在没登录时也已经存在，拿"cookie 非空"当已登录，会让设置页在根本没登录
     * 的机器上显示一个账号。
     */
    fun hasSession(cookie: String?): Boolean =
        cookie.orEmpty().split(';').any { entry ->
            entry.substringBefore('=', "").trim() == "MUSIC_U" &&
                entry.substringAfter('=', "").trim().isNotEmpty()
        }
}
