package com.maodouchat.server.service

// 消息与聊天功能开关簇：原 RuntimeConfigService 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。

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


fun RuntimeConfigService.isStickersEnabled(): Boolean =
    getBoolean(KEY_STICKERS_ENABLED, true)


fun RuntimeConfigService.isGifSendEnabled(): Boolean =
    getBoolean(KEY_GIF_SEND_ENABLED, true)


fun RuntimeConfigService.isVoiceMessagesEnabled(): Boolean =
    getBoolean(KEY_VOICE_MESSAGES_ENABLED, true)


fun RuntimeConfigService.isImageSendEnabled(): Boolean =
    getBoolean(KEY_IMAGE_SEND_ENABLED, true)


fun RuntimeConfigService.isVideoSendEnabled(): Boolean =
    getBoolean(KEY_VIDEO_SEND_ENABLED, true)


fun RuntimeConfigService.isFileShareEnabled(): Boolean =
    getBoolean(KEY_FILE_SHARE_ENABLED, true)


fun RuntimeConfigService.isLinkPreviewEnabled(): Boolean =
    getBoolean(KEY_LINK_PREVIEW_ENABLED, true)


fun RuntimeConfigService.isLiveLocationEnabled(): Boolean =
    getBoolean(KEY_LIVE_LOCATION_ENABLED, true)


fun RuntimeConfigService.isStaticLocationEnabled(): Boolean =
    getBoolean(KEY_STATIC_LOCATION_ENABLED, true)


fun RuntimeConfigService.isSpoilerMediaEnabled(): Boolean =
    getBoolean(KEY_SPOILER_MEDIA_ENABLED, true)


fun RuntimeConfigService.isAutoDownloadEnabled(): Boolean =
    getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, true)


fun RuntimeConfigService.isContactCardEnabled(): Boolean =
    getBoolean(KEY_CONTACT_CARD_ENABLED, true)


fun RuntimeConfigService.isMentionsEnabled(): Boolean =
    getBoolean(KEY_MENTIONS_ENABLED, true)


fun RuntimeConfigService.isDisappearingMessagesEnabled(): Boolean =
    getBoolean(KEY_DISAPPEARING_MESSAGES_ENABLED, true)
