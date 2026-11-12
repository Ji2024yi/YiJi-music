package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import com.music.bitchord.data.netease.NeteaseAuth
import com.music.bitchord.data.netease.NeteaseLikes

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Two live here: the YouTube Music session cookie, and — if the user turns on
 * the Discord integration — that account's own bearer token. Neither is a
 * password: the Google one is typed into accounts.google.com inside a WebView,
 * and the Discord one is read out of a completed login session. But both grant
 * full access to their account, so they don't go in the plain prefs the
 * scrobbler tokens use.
 *
 * A restored file whose keyset this device can't unwrap is recreated, and a
 * Keystore that can't be used at all degrades to plain prefs rather than
 * crashing on launch; see [EncryptedPrefs]. Kept out of backups for the first
 * reason, in backup_rules.xml and data_extraction_rules.xml.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "bitchord_auth", "bitchord_auth_plain")

    /**
     * 启动时把网易云的凭据发布到网络层。
     *
     * [AuthStore] 在几个地方各自构造（Application、PlaybackService、MainViewModel），
     * 而这个值全局只有一份，所以放在这里发布：不管谁先构造，网络层拿到的都是同一个
     * 答案，也不会有第二个类去读这个 key。
     */
    init {
        val stored = prefs.getString(KEY_NETEASE_COOKIE, null)
        NeteaseAuth.cookie = stored
        NeteaseAuth.signedIn.value = NeteaseAuth.hasSession(stored)
        NeteaseAuth.nickname.value = prefs.getString(KEY_NETEASE_NICKNAME, "").orEmpty()
        // uid 一起恢复。没有它 `/api/user/playlist` 只能回 400，
        // 「我的歌单 / 我的喜欢」整条同步链就断在第一个请求上。
        NeteaseAuth.userId = prefs.getString(KEY_NETEASE_UID, null)?.toLongOrNull() ?: 0L
        // 设备身份。没有它，网易云把所有登录类请求判成「环境异常」。
        // 生成一次就存下来，**不能每次随机**——每次换一个等于每次都换一台设备。
        val savedDevice = prefs.getString(KEY_NETEASE_DEVICE, null)
            ?.takeIf { it.length == DEVICE_ID_LENGTH }
        NeteaseAuth.deviceId = savedDevice ?: UUID.randomUUID().toString().replace("-", "")
            .also { prefs.edit().putString(KEY_NETEASE_DEVICE, it).apply() }
    }

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    /**
     * The durable account registry. Credentials remain in this encrypted store;
     * the old single-cookie entry is migrated lazily so an update never logs a
     * listener out.
     */
    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs.edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val existing = sessions
        val index = existing.indexOfFirst { it.accountId == session.accountId }
        val next = if (index >= 0) {
            existing.toMutableList().also { it[index] = session }
        } else {
            existing + session
        }
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        prefs.edit().putString(KEY_COOKIE, fallback?.cookie).apply()
        BrowserSession.clearGoogleCookies()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /**
     * The WebDAV server password. Encrypted like every other credential here:
     * a backup export carries the server URL and username in plain prefs, but
     * never this.
     */
    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    /**
     * The SMB share password. Encrypted like every other credential here:
     * exports carry the server, share and username in plain prefs, but never
     * this.
     */
    var smbPassword: String?
        get() = prefs.getString(KEY_SMB_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SMB_PASSWORD, value).apply()

    /**
     * 网易云的登录 cookie（`MUSIC_U` 等），加密存。
     *
     * 它跟 YouTube 的 cookie 是同一种东西：不是密码，但拿到它就能以这个账号的
     * 身份调接口，所以不进明文 prefs，也不进备份（见类注释里的那条理由）。
     *
     * 写的时候顺手发布到 [NeteaseAuth]：网络层在 IO 线程上，没有 Context 可以来
     * 开这个加密存储。
     */
    var neteaseCookie: String?
        get() = prefs.getString(KEY_NETEASE_COOKIE, null)
        set(value) {
            prefs.edit().putString(KEY_NETEASE_COOKIE, value).apply()
            NeteaseAuth.cookie = value
            NeteaseAuth.signedIn.value = NeteaseAuth.hasSession(value)
        }

    /**
     * 账号的数字 uid，跟 cookie 一起持久化。
     *
     * `/api/user/playlist?uid=` 缺了它就回 `{"code":400,"message":"请求参数错误"}`，
     * 而它原来只活在 [NeteaseAuth.userId] 这个进程内变量里——重启就没了，
     * 只能再赌一次 `/api/nuser/account/get`。存下来之后，歌单与喜欢同步不再依赖
     * 那一次请求的运气。
     */
    var neteaseUid: Long
        get() = prefs.getString(KEY_NETEASE_UID, null)?.toLongOrNull() ?: 0L
        set(value) {
            prefs.edit().putString(KEY_NETEASE_UID, value.toString()).apply()
            NeteaseAuth.userId = value
        }

    /** 显示用的昵称，不是凭据，但也没必要放进明文 prefs——跟 cookie 放一起最省事。 */
    var neteaseNickname: String?
        get() = prefs.getString(KEY_NETEASE_NICKNAME, null)
        set(value) {
            prefs.edit().putString(KEY_NETEASE_NICKNAME, value).apply()
            NeteaseAuth.nickname.value = value.orEmpty()
        }

    /**
     * 退出网易云。
     *
     * 一并清掉浏览器里的那份：那个 jar 是进程级的，留着它等于留着"下次点登录直接
     * 被当成刚退出的那个号"——跟 [signOut] 里 Google 那段是同一个坑。
     */
    fun signOutNetease() {
        prefs.edit()
            .remove(KEY_NETEASE_COOKIE)
            .remove(KEY_NETEASE_NICKNAME)
            .remove(KEY_NETEASE_UID)
            .apply()
        NeteaseAuth.cookie = null
        NeteaseAuth.signedIn.value = false
        NeteaseAuth.nickname.value = ""
        // uid 也是账号级的，留着会让下一个账号拿上个号的 uid 去问歌单。
        NeteaseAuth.userId = 0L
        // 失效标记跟着账号走，退出即复位。
        NeteaseAuth.sessionExpired.value = false
        // 心形和资料库入口都挂在账号上，退出时一并清掉，别把上个号
        // 的「我喜欢的音乐」留给下一个登录的人。
        NeteaseLikes.clear()
        BrowserSession.clearNeteaseCookies()
    }

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the cookie rather than in the plain settings because it is
     * only meaningful with that cookie and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] and [onNewSession] both clear it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = prefs.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs.getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = prefs.getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = prefs.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = prefs.edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        prefs.edit().putString(KEY_CHANNEL_NAME, name).apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() = prefs.edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    /**
     * A fresh login lands here. The cookie is new, so any channel chosen under
     * the old one names an identity this session cannot act as.
     */
    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    /**
     * Signs out of YouTube Music only — the Discord login is a separate account.
     */
    fun signOut() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).remove(KEY_ACTIVE_PROFILE).apply()
        clearChannel()
        // The in-app browser keeps its own copy of the Google login, and a
        // sign-out that leaves it in place is not one: the next sign-in is
        // waved straight through as the account just signed out of, with no
        // opportunity to choose another. See [BrowserSession].
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         *
         * Matched on the cookie *name*, which reads as pedantry and is not. The
         * test used to be `cookie.contains("SAPISID")`, and `__Secure-3PAPISID`
         * contains "SAPISID" — so a jar holding only the `__Secure-` forms, which
         * is what a partitioned-cookie login produces, passed a check for a
         * cookie it did not have. The app then declared itself signed in and made
         * every request unsigned, which Google answers as a stranger. Library
         * reads degraded quietly and history was never written at all.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"
        private const val KEY_NETEASE_COOKIE = "netease_cookie"
        private const val KEY_NETEASE_NICKNAME = "netease_nickname"
        private const val KEY_NETEASE_UID = "netease_uid"
        private const val KEY_NETEASE_DEVICE = "netease_device_id"

        /** `_ntes_nuid` 是 32 位十六进制，别的长度都当没存过。 */
        private const val DEVICE_ID_LENGTH = 32
    }
}
