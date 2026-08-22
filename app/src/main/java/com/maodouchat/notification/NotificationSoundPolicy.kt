package com.maodouchat.notification

/**
 * 通知声音生效判定。
 *
 * 服务端 `notification_sound_enabled`（RuntimeFlags.NOTIFICATION_SOUND）此前只在设置页
 * 拦截开关（管理员关闭后用户无法再开启），但通知实际发声路径从未读取该 flag——
 * 存量用户已开启的声音在管理员关掉后依然会响。此处把「flag 与用户偏好同时为真才发声」
 * 收敛为纯函数，供 AppNotifier 各通知路径统一调用。
 */
object NotificationSoundPolicy {

    /** 消息/互动/公告等普通通知的声音开关：服务端 flag 与用户偏好同时为真才发声。 */
    fun messageSoundEnabled(runtimeFlagEnabled: Boolean, userPreferenceEnabled: Boolean): Boolean =
        runtimeFlagEnabled && userPreferenceEnabled

    /**
     * 后台补发托盘通知（BacklogSyncWorker）的发声判定。
     * 在 [messageSoundEnabled] 之上再叠加会话静音：静音会话即使 flag/偏好都开也不响。
     */
    fun trayMessageSoundEnabled(
        runtimeFlagEnabled: Boolean,
        userPreferenceEnabled: Boolean,
        chatMuted: Boolean,
    ): Boolean =
        messageSoundEnabled(runtimeFlagEnabled, userPreferenceEnabled) && !chatMuted

    /** 来电铃声：服务端 ringtone flag（RuntimeFlags.RINGTONE）与用户偏好同时为真才响铃。 */
    fun ringtoneEnabled(runtimeFlagEnabled: Boolean, userPreferenceEnabled: Boolean): Boolean =
        runtimeFlagEnabled && userPreferenceEnabled

    /**
     * 当前打开的会话内短提示音（ToneGenerator，避开系统通知渠道 / Web autoplay）。
     * 需同时满足：应用内音效 flag、总通知开关、消息声音 flag+偏好。
     */
    fun inAppReceiveToneEnabled(
        inAppSoundsFlag: Boolean,
        notificationsEnabled: Boolean,
        notificationSoundFlag: Boolean,
        soundPreference: Boolean,
    ): Boolean =
        inAppSoundsFlag &&
            notificationsEnabled &&
            messageSoundEnabled(notificationSoundFlag, soundPreference)
}
