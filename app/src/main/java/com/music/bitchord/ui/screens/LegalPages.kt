package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 补丁 88：隐私政策与开源许可两页。
 *
 * 正文写在代码里而不是 strings.xml：这是法律文本，逐条改动都要能对上版本，而且
 * 不需要为它补 19 种语言的翻译（缺一条 LocaleStringsTest 就会红）。
 *
 * 两页共用同一套排版：小节标题 + 正文，整页可滚动，顶部让出宿主传进来的
 * [contentPadding]。
 */
@Composable
fun PrivacyPolicyPage(contentPadding: PaddingValues) {
    LegalPage(contentPadding) {
        LegalTitle("隐私政策")
        LegalMeta("更新日期：2026-11-11 · 适用于本应用的全部版本")

        LegalHeading("一、我们不收集什么")
        LegalBody(
            "本应用没有账号体系，不需要注册即可使用。没有第三方统计 SDK、没有广告 SDK，" +
                "不读取 IMEI / Android ID 等设备标识符，不做跨应用追踪；" +
                "不上传通讯录、位置、相册、账号信息或任何文件内容。",
        )

        LegalHeading("二、匿名使用统计（可关闭）")
        LegalBody(
            "为了知道有多少人在用、每天有多少人打开，应用会发送少量匿名记录。",
        )
        LegalBullet("一个随机生成的安装 id（首次启动时生成，卸载重装会换）")
        LegalBullet("事件类型、发生时间，以及按 UTC+8 计算的当天日期")
        LegalBullet("应用版本名与版本号")
        LegalBullet("设备品牌、型号，以及 Android 版本与 API 级别")
        LegalBody(
            "事件只有启动、活跃、回到前台、退出统计四种，只用来算日活、版本分布和留存。" +
                "不发账号、手机号、IMEI / Android ID、位置、听歌记录或任何文件内容。",
        )
        LegalBody(
            "没有第三方统计 SDK，这些记录不与任何账号关联，不用于画像或广告。" +
                "默认开启；不想被统计：设置 → 关于 → 匿名使用统计，关掉后立即停止，" +
                "本机队列与死信一并删除。",
        )

        LegalHeading("三、保存在你手机上的数据")
        LegalBullet("设置项、播放历史、收藏与「我喜欢的音乐」标记")
        LegalBullet("你自己选的头像与昵称（只存在本机）")
        LegalBullet("歌曲缓存、封面缓存（可随时清除）")
        LegalBullet("第三方服务的登录凭据，例如网易云音乐的 Cookie")
        LegalBullet("崩溃诊断日志：崩溃发生的时间、应用版本、设备型号、Android 版本与异常堆栈")
        LegalBody(
            "登录凭据用 Android Keystore 加密后保存在本机应用私有目录，只有本应用能读。" +
                "上述数据不会离开你的设备。",
        )
        LegalBody(
            "崩溃日志只写在应用私有目录，不会自动上传，也不会交给任何第三方服务。" +
                "写入前会移除网址、邮箱与长数字串，不含账号、歌名或听歌记录；最多保留最近 5 份。" +
                "只有你在设置里主动点「导出」时，它才会以文本文件存到你选的位置。",
        )

        LegalHeading("四、什么时候会联网")
        LegalBody("只在你主动使用某个功能时联网，而且只发给对应的那个服务：")
        LegalBullet("网易云音乐：登录凭据、你打开的歌单或输入的搜索词、取播放地址")
        LegalBullet("你自己填的 WebDAV / SMB / 一起听服务器：地址与凭据")
        LegalBullet("歌词服务：歌名、歌手、时长（仅用于匹配歌词）")
        LegalBullet("社区画布：你自己配置的清单地址")
        LegalBullet("GitHub：仅查询是否发布了新版本，不发送账号、设备或播放信息，也不下载安装包")
        LegalBody("这些请求走的是你选择的那个第三方服务，适用它自己的隐私政策。")

        LegalHeading("五、申请哪些权限")
        LegalBullet("网络：获取歌曲、封面与歌词")
        LegalBullet("通知与前台服务：后台播放、通知栏歌词")
        LegalBullet("读取媒体与文件：播放本机音乐、导出已下载的歌")
        LegalBody("不申请定位、通讯录、短信、相机、麦克风权限。")

        LegalHeading("六、第三方服务")
        LegalBody(
            "本应用是非官方客户端，与网易云音乐、Apple、Google 均无隶属关系，也未获其背书。" +
                "你在应用内使用这些服务时，它们各自的条款与隐私政策同样适用。",
        )

        LegalHeading("七、删除你的数据")
        LegalBody(
            "设置 → 下载 → 清除缓存，可以删掉全部缓存；设置 → 第三方账号 → 退出登录，" +
                "可以删掉保存的凭据；设置 → 导出数据 → 崩溃诊断日志右侧的清除按钮，可以删掉全部崩溃日志；" +
                "卸载本应用即删除全部本机数据（应用私有目录随之清空）。",
        )

        LegalHeading("八、政策变更")
        LegalBody("政策有变化会随版本更新在本页说明，重要变更会在更新日志里单独列出。")

        LegalHeading("九、联系我们")
        LegalBody("对本政策有疑问，请通过发布本应用的页面上的联系方式与我联系。")

        LegalHeading("English summary")
        LegalBody(
            "This app has no account system and collects no personal information. Everything it stores " +
                "(settings, history, favourites, caches, credentials, avatar) stays on your " +
                "device; credentials are encrypted with the Android Keystore. It only reaches " +
                "the network when you use a feature that needs it, and then only talks to that " +
                "service — NetEase Cloud Music, a WebDAV/SMB/party server you configured, a " +
                "lyrics service, or a community-canvas manifest you added. No third-party analytics " +
                "SDK, no ads, no device identifiers and no cross-app tracking. It is an unofficial " +
                "client with no affiliation to NetEase, Apple or Google. Uninstalling removes all " +
                "local data.",
        )
        LegalBody(
            "Anonymous usage stats are on by default and can be switched off in Settings → " +
                "About → Anonymous usage stats. Each record contains a random install id, the " +
                "event type, a timestamp, the UTC+8 day, the app version name and code, the " +
                "device brand and model, and the Android version and API level. Events are " +
                "limited to launch, active, foreground and opt_out, and are used only for daily " +
                "active installs, version distribution and retention. No account, phone number, " +
                "IMEI/Android ID, location or listening history is sent.",
        )
        LegalBody(
            "It also asks GitHub once per launch whether a newer release exists. That request " +
                "carries no account or device data, and the app never downloads or installs an APK itself.",
        )
        LegalBody(
            "Crash diagnostics are written to the app's private directory only: timestamp, app " +
                "version, device model, Android version and a redacted stack trace, with URLs, " +
                "email addresses and long numbers removed. They are never uploaded or sent to any " +
                "third party, are capped at five recent records, and can be exported or cleared " +
                "by you in Settings → Export data → Crash diagnostics.",
        )
    }
}

@Composable
fun OpenSourceLicensePage(contentPadding: PaddingValues) {
    val opener = LocalUriHandler.current
    LegalPage(contentPadding) {
        LegalTitle("开源许可")
        LegalMeta("GNU General Public License v3.0")

        LegalBody(
            "本应用基于开源项目 BitChord 修改而来，按 GNU 通用公共许可证第 3 版（GPLv3）授权。",
        )

        LegalHeading("原项目")
        LegalBody("BitChord — github.com/kushagrasinghx/BitChord\n版权所有 © BitChord 的作者与贡献者。")

        LegalHeading("本版本的修改")
        LegalBullet("中文界面与本地化调整")
        LegalBullet("接入网易云音乐音源（搜索、歌单、漫游、收藏）")
        LegalBullet("移除 Google 账号登录与随之而来的在线服务")
        LegalBullet("设置页合并分类、歌单页改版等界面调整")
        LegalBody("本版本是非官方修改版，与原作者无关，不代表原作者的观点。")

        LegalHeading("你的权利")
        LegalBody(
            "你可以自由运行、研究、修改和再分发本软件。再分发时须遵守 GPLv3：保留同样的许可、" +
                "保留版权声明、标明你做了哪些修改，并向接收者提供对应的完整源码。",
        )

        LegalHeading("源码")
        LegalBody(
            "按 GPLv3 第 6 条，本版本的完整源码随安装包一并以相同许可提供：",
        )
        LegalBullet(SOURCE_URL)
        LegalBody("（本仓库即完整对应源码；如果链接失效，请通过发布页联系我索取。）")

        LegalHeading("担保")
        LegalBody(
            "本软件按「现状」提供，不附带任何明示或默示的担保，包括但不限于适销性与特定用途适用性。" +
                "使用本软件产生的任何后果由使用者自行承担。",
        )

        LegalHeading("完整许可文本")
        LegalBody("GNU General Public License v3.0 全文：https://www.gnu.org/licenses/gpl-3.0.txt")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点击打开",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { opener.openUri("https://www.gnu.org/licenses/gpl-3.0.txt") },
        )
    }
}

/**
 * 补丁 98：源码地址改成公开仓库。GPLv3 要求获取源码不附加口令，
 * 原来的私有下载目录需要认证，不满足「Corresponding Source 可获取」。
 */
private const val SOURCE_URL = "https://github.com/Ji2024yi/YiJi-music"

@Composable
private fun LegalPage(
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        content()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LegalTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LegalMeta(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun LegalHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun LegalBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 22.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun LegalBullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 22.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
    )
}
