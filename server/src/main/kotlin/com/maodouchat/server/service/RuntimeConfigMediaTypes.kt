package com.maodouchat.server.service

// 媒体类型开关簇：贴纸/GIF/语音/图片/视频/文件/链接预览/位置等媒体能力开关（原 RuntimeConfigMessaging 扩展按域拆出，零行为改动）。


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

fun RuntimeConfigService.isAutoDownloadEnabled(): Boolean =
    getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, true)

fun RuntimeConfigService.isContactCardEnabled(): Boolean =
    getBoolean(KEY_CONTACT_CARD_ENABLED, true)
