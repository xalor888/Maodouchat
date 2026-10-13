package com.maodouchat.server.service

// AI/媒体/通话/通知/系统开关簇：原 RuntimeConfigService 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。

fun RuntimeConfigService.isAiEnabled(): Boolean =
    getBoolean(KEY_AI_ENABLED, true)


/** 动态/评论 AI 审帖。默认关；聊天密文永不送模型。 */
fun RuntimeConfigService.isAiContentModerationEnabled(): Boolean =
    getBoolean(KEY_AI_CONTENT_MODERATION_ENABLED, false)


/** 每用户每日 token 预算（input + output）。超限时网关应返回 BudgetExceeded。 */
fun RuntimeConfigService.aiDailyTokenBudgetPerUser(): Long =
    getLong(KEY_AI_DAILY_TOKEN_BUDGET_PER_USER, 200_000L).coerceIn(0L, 1_000_000_000L)


/** 是否启用 AI 网关的瞬态错误重试与退避。 */
fun RuntimeConfigService.isAiRetryEnabled(): Boolean =
    getBoolean(KEY_AI_RETRY_ENABLED, true)


fun RuntimeConfigService.isMediaUploadEnabled(): Boolean =
    getBoolean(KEY_MEDIA_UPLOAD_ENABLED, true)


fun RuntimeConfigService.isBlindWatermarkEnabled(): Boolean =
    getBoolean(KEY_BLIND_WATERMARK_ENABLED, true)


fun RuntimeConfigService.isCallsEnabled(): Boolean =
    getBoolean(KEY_CALLS_ENABLED, true)


fun RuntimeConfigService.isVoiceCallEnabled(): Boolean =
    getBoolean(KEY_VOICE_CALL_ENABLED, true)


fun RuntimeConfigService.isVideoCallEnabled(): Boolean =
    getBoolean(KEY_VIDEO_CALL_ENABLED, true)


fun RuntimeConfigService.isPushNotificationsEnabled(): Boolean =
    getBoolean(KEY_PUSH_NOTIFICATIONS_ENABLED, true)


fun RuntimeConfigService.isNotificationSoundEnabled(): Boolean =
    getBoolean(KEY_NOTIFICATION_SOUND_ENABLED, true)


fun RuntimeConfigService.isRingtoneEnabled(): Boolean =
    getBoolean(KEY_RINGTONE_ENABLED, true)


fun RuntimeConfigService.isNotificationPreviewEnabled(): Boolean =
    getBoolean(KEY_NOTIFICATION_PREVIEW_ENABLED, true)


fun RuntimeConfigService.isTaskRemindersEnabled(): Boolean =
    getBoolean(KEY_TASK_REMINDERS_ENABLED, true)


fun RuntimeConfigService.isDndEnabled(): Boolean =
    getBoolean(KEY_DND_ENABLED, true)


fun RuntimeConfigService.isInAppSoundsEnabled(): Boolean =
    getBoolean(KEY_IN_APP_SOUNDS_ENABLED, true)


fun RuntimeConfigService.isHapticsEnabled(): Boolean =
    getBoolean(KEY_HAPTICS_ENABLED, true)


fun RuntimeConfigService.isChatAnimationsEnabled(): Boolean =
    getBoolean(KEY_CHAT_ANIMATIONS_ENABLED, true)


fun RuntimeConfigService.isNavTransitionsEnabled(): Boolean =
    getBoolean(KEY_NAV_TRANSITIONS_ENABLED, true)
