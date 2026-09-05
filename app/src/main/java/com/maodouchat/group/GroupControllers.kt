package com.maodouchat.group

import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceOutcome
import com.maodouchat.network.ApiService
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.network.GroupInviteResponse
import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拥有/可管理的 Bot 信息 UI 模型。
 */
data class GroupOwnedBotUi(
    val id: String,
    val name: String,
    val username: String,
)

/**
 * 群邀请控制器（U06）。
 * 独立管理群邀请码的获取、轮换、时效与最大次数配置。
 */
class GroupInviteController(
    private val tokenProvider: () -> String,
) {
    suspend fun fetchInvite(
        chatId: String,
        rotate: Boolean = false,
        expiresInSeconds: Long = 7L * 24L * 60L * 60L,
        maxUses: Int = 100,
    ): Result<GroupInviteResponse> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        ApiService.getOrCreateGroupInvite(
            token = token,
            chatId = chatId,
            rotate = rotate,
            expiresInSeconds = expiresInSeconds,
            maxUses = maxUses,
        )
    }

    suspend fun rotateInvite(
        chatId: String,
        expiresInSeconds: Long = 7L * 24L * 60L * 60L,
        maxUses: Int = 100,
    ): Result<GroupInviteResponse> = fetchInvite(
        chatId = chatId,
        rotate = true,
        expiresInSeconds = expiresInSeconds,
        maxUses = maxUses,
    )
}

/**
 * 群审计日志控制器（U06）。
 * 独立管理群成员与管理操作历史审计的分页加载与查询。
 */
class GroupAuditController(
    private val tokenProvider: () -> String,
) {
    suspend fun fetchAuditLogs(
        chatId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<List<GroupAuditLogDto>> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        ApiService.getGroupAudit(token, chatId, limit = limit, offset = offset)
    }
}

/**
 * 群机器人控制器（U06）。
 * 独立管理群内 Bot 的发现与邀请。
 */
class GroupBotController(
    private val tokenProvider: () -> String,
) {
    suspend fun fetchCandidateBots(): Result<List<GroupOwnedBotUi>> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        ApiService.listBots(token).map { raw ->
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val name = o.optString("name").trim()
                    val username = o.optString("username").trim()
                    val enabled = o.optBoolean("enabled", true)
                    if (id.isBlank() || !enabled) continue
                    add(GroupOwnedBotUi(id = id, name = name.ifBlank { username }, username = username))
                }
            }
        }
    }

    suspend fun inviteBot(chatId: String, botId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        ApiService.inviteBotToChat(token, chatId, botId)
    }
}

/**
 * 群加密健康控制器（U06）。
 * 独立管理 Sender Key 的覆盖率评估、本地分发有效性检查与按需重分发。
 */
class GroupEncryptionHealthController(
    private val maintenanceCoordinator: GroupSenderKeyMaintenanceCoordinator,
    private val tokenProvider: () -> String,
) {
    suspend fun runManual(
        chatId: String,
        epoch: Long,
    ): GroupSenderKeyMaintenanceOutcome = withContext(Dispatchers.IO) {
        maintenanceCoordinator.runManual(chatId, epoch)
    }

    suspend fun runAutomatic(
        chatId: String,
        epoch: Long,
        currentStatus: SenderKeyDistributionStatusDto? = null,
        localHasSenderKeyHint: Boolean? = null,
    ): GroupSenderKeyMaintenanceOutcome = withContext(Dispatchers.IO) {
        maintenanceCoordinator.runAutomatic(chatId, epoch, currentStatus, localHasSenderKeyHint)
    }

    suspend fun fetchSenderKeyStatus(
        chatId: String,
        epoch: Long? = null,
    ): Result<SenderKeyDistributionStatusDto> = withContext(Dispatchers.IO) {
        val token = tokenProvider().trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("session_missing"))
        ApiService.getSenderKeyDistributionStatus(token, chatId, epoch = epoch)
    }
}
