package com.maodouchat.server.service

// 用户与身份类导出。

import com.maodouchat.server.repository.identityUsers
import com.maodouchat.server.repository.onlinePresence
import com.maodouchat.server.repository.privacyFlags
import com.maodouchat.server.repository.pushTokens
import com.maodouchat.server.repository.restrictedUsers
import com.maodouchat.server.repository.sessionsSummary
import com.maodouchat.server.repository.totpUsers
import com.maodouchat.server.repository.users

fun AdminExportService.pushTokensCsv(limit: Int) = csv(
    "userId,deviceId,platform,tokenPrefix,timezoneOffsetMinutes,updatedAt",
    repository.pushTokens(limit),
)

fun AdminExportService.usersCsv(limit: Int) = csv(
    "id,name,email,status,isOnline,isModerator,suspendedUntil,lastSeen",
    repository.users(limit),
)

fun AdminExportService.sessionsSummaryCsv(limit: Int) = csv(
    "userId,name,isOnline,activeRefreshSessions,lastSeen",
    repository.sessionsSummary(limit),
)

fun AdminExportService.onlinePresenceCsv(limit: Int) = csv(
    "userId,isOnline,lastSeen,showOnline",
    repository.onlinePresence(limit),
)

fun AdminExportService.privacyFlagsCsv(limit: Int) = csv(
    "userId,showOnline,showStatus,searchable",
    repository.privacyFlags(limit),
)

fun AdminExportService.identityUsersCsv(limit: Int) = csv(
    "userId,searchable,showOnline,totpEnabled,emailHint",
    repository.identityUsers(limit),
)

fun AdminExportService.totpUsersCsv(limit: Int) = csv(
    "userId,totpEnabled,emailHint",
    repository.totpUsers(limit),
)

fun AdminExportService.restrictedUsersCsv(limit: Int) = csv(
    "userId,messageRestrictedUntil,postRestrictedUntil,suspendedUntil",
    repository.restrictedUsers(limit),
)
