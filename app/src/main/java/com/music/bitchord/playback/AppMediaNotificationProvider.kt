package com.music.bitchord.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.music.bitchord.R

/**
 * 媒体通知回到默认样式，只把**小图标**换回 App 自己的。
 *
 * v45 把旧的歌词 provider 删掉后，媒体通知落回 Media3 的通用图标，他那边看到的
 * 就是"应用图标变了"——桌面图标没动，变的是通知栏这一枚。这里不覆写任何文字
 * （歌词走 `MediaMetadata.title`，见 `PlaybackService.publishLyricsToNotification`），
 * 只做这一件事，行为与默认 provider 完全一致。
 */
@UnstableApi
class AppMediaNotificationProvider(
    context: Context,
    channelId: String,
) : DefaultMediaNotificationProvider(
    context,
    { DEFAULT_NOTIFICATION_ID },
    channelId,
    R.string.playback_channel_name,
) {

    init {
        setSmallIcon(R.drawable.ic_notification_logo)
    }
}
