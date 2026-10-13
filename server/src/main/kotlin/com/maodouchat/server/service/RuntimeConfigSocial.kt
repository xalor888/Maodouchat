package com.maodouchat.server.service

// 社交/发现/群组功能开关簇：原 RuntimeConfigService 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。

fun RuntimeConfigService.isPostsEnabled(): Boolean =
    getBoolean(KEY_POSTS_ENABLED, true)


fun RuntimeConfigService.isFriendRequestsEnabled(): Boolean =
    getBoolean(KEY_FRIEND_REQUESTS_ENABLED, true)


fun RuntimeConfigService.isChatFoldersEnabled(): Boolean =
    getBoolean(KEY_CHAT_FOLDERS_ENABLED, true)


fun RuntimeConfigService.isGlobalSearchEnabled(): Boolean =
    getBoolean(KEY_GLOBAL_SEARCH_ENABLED, true)


fun RuntimeConfigService.isNearbyEnabled(): Boolean =
    getBoolean(KEY_NEARBY_ENABLED, false)


fun RuntimeConfigService.isChannelsEnabled(): Boolean =
    getBoolean(KEY_CHANNELS_ENABLED, true)


fun RuntimeConfigService.isPollsEnabled(): Boolean =
    getBoolean(KEY_POLLS_ENABLED, true)


fun RuntimeConfigService.isBlockReportEnabled(): Boolean =
    getBoolean(KEY_BLOCK_REPORT_ENABLED, true)


fun RuntimeConfigService.isGroupPlayEnabled(): Boolean =
    getBoolean(KEY_GROUP_PLAY_ENABLED, true)


fun RuntimeConfigService.isGroupInvitesEnabled(): Boolean =
    getBoolean(KEY_GROUP_INVITES_ENABLED, true)


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


fun RuntimeConfigService.maxGroupSize(): Int =
    getInt(KEY_MAX_GROUP_SIZE, 200).coerceIn(2, 5000)


fun RuntimeConfigService.isPresenceEnabled(): Boolean =
    getBoolean(KEY_PRESENCE_ENABLED, true)
