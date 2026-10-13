package com.maodouchat.server.repository

import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.model.PinnedMessageResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 置顶消息查询。

fun PinnedMessageRepository.list(chatId: String): List<PinnedMessageResponse> {
    return transaction {
        PinnedMessages.selectAll()
            .where { PinnedMessages.chatId eq chatId }
            .orderBy(PinnedMessages.pinnedAt to SortOrder.DESC)
            .map { row ->
                PinnedMessageResponse(
                    chatId = row[PinnedMessages.chatId],
                    messageId = row[PinnedMessages.messageId],
                    pinnedBy = row[PinnedMessages.pinnedBy],
                    pinnedAt = row[PinnedMessages.pinnedAt]
                )
            }
    }
}


internal fun PinnedMessageRepository.listInTx(chatId: String): List<PinnedMessageResponse> {
    return PinnedMessages.selectAll()
        .where { PinnedMessages.chatId eq chatId }
        .orderBy(PinnedMessages.pinnedAt to SortOrder.DESC)
        .map { row ->
            PinnedMessageResponse(
                chatId = row[PinnedMessages.chatId],
                messageId = row[PinnedMessages.messageId],
                pinnedBy = row[PinnedMessages.pinnedBy],
                pinnedAt = row[PinnedMessages.pinnedAt]
            )
        }
}
