package com.maodouchat.server.service

// 聊天客户端功能开关簇：文件夹/归档/固定/静音/锁定/草稿等聊天体验开关（原 RuntimeConfigSocial 扩展按域拆出，零行为改动）。


fun RuntimeConfigService.isChatFoldersEnabled(): Boolean =
    getBoolean(KEY_CHAT_FOLDERS_ENABLED, true)

fun RuntimeConfigService.isGlobalSearchEnabled(): Boolean =
    getBoolean(KEY_GLOBAL_SEARCH_ENABLED, true)

fun RuntimeConfigService.isChatArchiveEnabled(): Boolean =
    getBoolean(KEY_CHAT_ARCHIVE_ENABLED, true)

fun RuntimeConfigService.isChatPinEnabled(): Boolean =
    getBoolean(KEY_CHAT_PIN_ENABLED, true)

fun RuntimeConfigService.isMarkedUnreadEnabled(): Boolean =
    getBoolean(KEY_MARKED_UNREAD_ENABLED, true)

fun RuntimeConfigService.isChatMuteEnabled(): Boolean =
    getBoolean(KEY_CHAT_MUTE_ENABLED, true)

fun RuntimeConfigService.isChatLockEnabled(): Boolean =
    getBoolean(KEY_CHAT_LOCK_ENABLED, true)

fun RuntimeConfigService.isChatDraftsEnabled(): Boolean =
    getBoolean(KEY_CHAT_DRAFTS_ENABLED, true)

fun RuntimeConfigService.isChatWallpaperEnabled(): Boolean =
    getBoolean(KEY_CHAT_WALLPAPER_ENABLED, true)

fun RuntimeConfigService.isChatFontScaleEnabled(): Boolean =
    getBoolean(KEY_CHAT_FONT_SCALE_ENABLED, true)

fun RuntimeConfigService.isUnreadPriorityEnabled(): Boolean =
    getBoolean(KEY_UNREAD_PRIORITY_ENABLED, true)

fun RuntimeConfigService.isNudgeEnabled(): Boolean =
    getBoolean(KEY_NUDGE_ENABLED, true)
