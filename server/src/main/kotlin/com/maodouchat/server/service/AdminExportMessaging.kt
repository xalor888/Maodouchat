package com.maodouchat.server.service

// 消息与会话类导出。

import com.maodouchat.server.repository.chatSettings
import com.maodouchat.server.repository.chats
import com.maodouchat.server.repository.disappearingChats
import com.maodouchat.server.repository.messageStats
import com.maodouchat.server.repository.mutedChats
import com.maodouchat.server.repository.pinnedMessages
import com.maodouchat.server.repository.pollVotes
import com.maodouchat.server.repository.polls

/**
 * 消息统计导出。排序刻意放在**编码之后**：原实现是
 * `map { listOf(csvCell(t), csvCell(c)).joinToString(",") }.sorted()`，
 * 排的是编码后的整行字符串，不是原始 kind。为保持逐字一致，这里照搬。
 */
fun AdminExportService.messageStatsCsv(): CsvExport {
    val stats = repository.messageStats()
    val encoded = stats.byKind.map { encodeRow(it) }.sorted()
    return CsvExport(
        body = buildString {
            appendLine("type,count")
            encoded.forEach { appendLine(it) }
            appendLine(listOf(csvCell("TOTAL"), csvCell(stats.total)).joinToString(","))
        },
        rowCount = encoded.size + 1,
    )
}

fun AdminExportService.pollsCsv(limit: Int) = csv(
    "id,chatId,creatorId,question,multi,anonymous,closed,voteRows,createdAt,closesAt",
    repository.polls(limit),
)

fun AdminExportService.pollVotesCsv(limit: Int) = csv(
    "pollId,userId,optionIndex,votedAt",
    repository.pollVotes(limit),
)

fun AdminExportService.pinnedMessagesCsv(limit: Int) = csv(
    "chatId,messageId,pinnedBy,pinnedAt",
    repository.pinnedMessages(limit),
)

fun AdminExportService.chatsCsv(limit: Int) = csv(
    "id,type,title,memberCount,memberRevision,disappearingSeconds",
    repository.chats(limit),
)

fun AdminExportService.chatSettingsCsv(limit: Int) = csv(
    "userId,chatId,pinnedAt,notificationsMuted,archived,markedUnread,updatedAt",
    repository.chatSettings(limit),
)

fun AdminExportService.disappearingChatsCsv(limit: Int) = csv(
    "chatId,isGroup,groupName,disappearingSeconds",
    repository.disappearingChats(limit),
)

fun AdminExportService.mutedChatsCsv(limit: Int) = csv(
    "userId,chatId,notificationsMuted,updatedAt",
    repository.mutedChats(limit),
)
