package com.maodouchat.server.service

// 社交功能开关簇：动态/好友/附近/频道/投票/群玩法/群邀请等社交能力开关（原 RuntimeConfigSocial 扩展按域拆出，零行为改动）。


fun RuntimeConfigService.isPostsEnabled(): Boolean =
    getBoolean(KEY_POSTS_ENABLED, true)

fun RuntimeConfigService.isFriendRequestsEnabled(): Boolean =
    getBoolean(KEY_FRIEND_REQUESTS_ENABLED, true)

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

fun RuntimeConfigService.isPresenceEnabled(): Boolean =
    getBoolean(KEY_PRESENCE_ENABLED, true)

fun RuntimeConfigService.maxGroupSize(): Int =
    getInt(KEY_MAX_GROUP_SIZE, 200).coerceIn(2, 5000)
