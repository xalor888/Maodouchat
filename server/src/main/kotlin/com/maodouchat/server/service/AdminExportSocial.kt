package com.maodouchat.server.service

// 社交与 Bot 类导出。

import com.maodouchat.server.repository.blockedUsers
import com.maodouchat.server.repository.botCommandStats
import com.maodouchat.server.repository.bots
import com.maodouchat.server.repository.friendships
import com.maodouchat.server.repository.groupInvites

fun AdminExportService.botsCsv(limit: Int) = csv(
    "id,name,username,ownerUserId,tokenPrefix,webhookUrl,enabled,createdAt,updatedAt",
    repository.bots(limit),
)

fun AdminExportService.botCommandStatsCsv(limit: Int) = csv(
    "id,botId,chatId,userId,command,createdAt",
    repository.botCommandStats(limit),
)

fun AdminExportService.friendshipsCsv(limit: Int) = csv(
    "userLowId,userHighId,createdAt",
    repository.friendships(limit),
)

fun AdminExportService.blockedUsersCsv(limit: Int) = csv(
    "blockerId,blockedId",
    repository.blockedUsers(limit),
)

fun AdminExportService.groupInvitesCsv(limit: Int) = csv(
    "chatId,tokenPrefix,expiresAt,maxUses,useCount",
    repository.groupInvites(limit),
)
