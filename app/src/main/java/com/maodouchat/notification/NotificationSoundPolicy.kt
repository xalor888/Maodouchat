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

    /** 来电铃声：服务端 ringtone flag（RuntimeFlags.RINGTONE）与用户偏好同时为真才响铃。 */
    fun ringtoneEnabled(runtimeFlagEnabled: Boolean, userPreferenceEnabled: Boolean): Boolean =
        runtimeFlagEnabled && userPreferenceEnabled
}
