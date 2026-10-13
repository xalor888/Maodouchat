package com.maodouchat.server.service

// 消息功能开关簇：发送/编辑/撤回/回执/提及等消息能力开关（原 RuntimeConfigMessaging 扩展按域拆出，零行为改动）。


fun RuntimeConfigService.maxMessagePerMinute(): Int =
    getInt(KEY_MAX_MESSAGE_PER_MIN, 180).coerceIn(10, 600)

fun RuntimeConfigService.isSilentSendEnabled(): Boolean =
    getBoolean(KEY_SILENT_SEND_ENABLED, true)

fun RuntimeConfigService.isScheduledMessagesEnabled(): Boolean =
    getBoolean(KEY_SCHEDULED_MESSAGES_ENABLED, true)

fun RuntimeConfigService.isViewOnceEnabled(): Boolean =
    getBoolean(KEY_VIEW_ONCE_ENABLED, true)

fun RuntimeConfigService.isMarkdownEnabled(): Boolean =
    getBoolean(KEY_MARKDOWN_ENABLED, true)

fun RuntimeConfigService.isTypingIndicatorsEnabled(): Boolean =
    getBoolean(KEY_TYPING_INDICATORS_ENABLED, true)

fun RuntimeConfigService.isReadReceiptsEnabled(): Boolean =
    getBoolean(KEY_READ_RECEIPTS_ENABLED, true)

fun RuntimeConfigService.isMessageStarringEnabled(): Boolean =
    getBoolean(KEY_MESSAGE_STARRING_ENABLED, true)

fun RuntimeConfigService.isChatExportEnabled(): Boolean =
    getBoolean(KEY_CHAT_EXPORT_ENABLED, false)

fun RuntimeConfigService.isMessageForwardingEnabled(): Boolean =
    getBoolean(KEY_MESSAGE_FORWARDING_ENABLED, true)

fun RuntimeConfigService.isMessageEditEnabled(): Boolean =
    getBoolean(KEY_MESSAGE_EDIT_ENABLED, true)

fun RuntimeConfigService.isMessagePinEnabled(): Boolean =
    getBoolean(KEY_MESSAGE_PIN_ENABLED, true)

fun RuntimeConfigService.isMessageRevokeEnabled(): Boolean =
    getBoolean(KEY_MESSAGE_REVOKE_ENABLED, true)

fun RuntimeConfigService.isReactionsEnabled(): Boolean =
    getBoolean(KEY_REACTIONS_ENABLED, true)

fun RuntimeConfigService.isMentionsEnabled(): Boolean =
    getBoolean(KEY_MENTIONS_ENABLED, true)

fun RuntimeConfigService.isSpoilerMediaEnabled(): Boolean =
    getBoolean(KEY_SPOILER_MEDIA_ENABLED, true)

fun RuntimeConfigService.isDisappearingMessagesEnabled(): Boolean =
    getBoolean(KEY_DISAPPEARING_MESSAGES_ENABLED, true)
