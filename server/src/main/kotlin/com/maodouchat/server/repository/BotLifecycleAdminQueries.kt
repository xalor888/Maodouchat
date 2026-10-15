package com.maodouchat.server.repository

// bot 管理/查询侧：会话归属查询与管理后台启停/列表（原 BotLifecycle 簇成员按域迁出，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update


/** 8.33：bot 所在全部会话（删除 bot 后需要向这些群广播 memberRevision）。 */
fun BotRepository.groupChatIdsFor(botId: String): List<String> = transaction {
    ChatParticipants.select(ChatParticipants.chatId)
        .where { ChatParticipants.userId eq botId }
        .map { it[ChatParticipants.chatId] }
        .distinct()
}


/**
 * 管理后台专用启停：不校验 owner（管理员跨 owner 操作），只按 botId 更新并回读。
 * 返回 null 表示 bot 不存在。此前这段写在 `plugins/AdminDiagnosticsRouting.kt` 的裸事务里。
 */
fun BotRepository.setAdminEnabled(botId: String, enabled: Boolean): BotDto? = transaction {
    val updated = BotApps.update({ BotApps.id eq botId }) {
        it[BotApps.enabled] = enabled
        it[updatedAt] = System.currentTimeMillis()
    }
    if (updated != 1) return@transaction null
    BotApps.selectAll().where { BotApps.id eq botId }.firstOrNull()?.toDto()
}


fun BotRepository.adminList(limit: Int = 50, offset: Int = 0): List<BotDto> = transaction {
    BotApps.selectAll()
        .orderBy(
            BotApps.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            BotApps.id to org.jetbrains.exposed.sql.SortOrder.DESC
        )
        .limit(limit.coerceIn(1, 200), offset.toLong().coerceAtLeast(0))
        .map { it.toDto() }
}
