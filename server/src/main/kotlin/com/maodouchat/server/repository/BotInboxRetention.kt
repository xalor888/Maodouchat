package com.maodouchat.server.repository

import com.maodouchat.server.db.BotUpdateInbox
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll

// 单 bot 收件箱积压上限：超限按 FIFO 淘汰最旧，防止停轮询 bot 的消息洪泛廉价放大 DB 写入。
internal const val MAX_INBOX_PENDING_PER_BOT = 500L
// 收件箱事件 JSON 上限：超限拒写（截断会从多字节字符/JSON token 中间切断，产生损坏行）。
internal const val MAX_UPDATE_JSON_CHARS = 16_000

// 须在事务内调用。
internal fun BotRepository.evictOldestInboxLocked(botId: String) {
    val pending = BotUpdateInbox.selectAll().where { BotUpdateInbox.botId eq botId }.count()
    if (pending < MAX_INBOX_PENDING_PER_BOT) return
    val overflow = (pending - MAX_INBOX_PENDING_PER_BOT + 1L).coerceAtLeast(1L).toInt()
    val oldestIds = BotUpdateInbox.selectAll()
        .where { BotUpdateInbox.botId eq botId }
        .orderBy(BotUpdateInbox.id to SortOrder.ASC)
        .limit(overflow)
        .map { it[BotUpdateInbox.id] }
    if (oldestIds.isNotEmpty()) {
        BotUpdateInbox.deleteWhere { BotUpdateInbox.id inList oldestIds }
    }
}
