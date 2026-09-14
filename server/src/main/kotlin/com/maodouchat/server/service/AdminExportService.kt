package com.maodouchat.server.service

import com.maodouchat.server.repository.AdminExportRepository

/**
 * M2：管理后台 CSV 导出的组装层。
 *
 * 职责边界：
 * - [AdminExportRepository] 负责取数（SQL/Exposed 边界）；
 * - 本类负责 CSV 编码（含 Excel 公式注入防护）与表头，并回报数据行数供审计使用；
 * - `AdminExportsRouting` 只做鉴权、参数解析、审计与响应——不再持有任何 `transaction {`。
 *
 * 依赖方向：`service → repository`（允许）；本类不 import `plugins`（棘轮守着）。
 */
class AdminExportService(
    private val repository: AdminExportRepository = AdminExportRepository(),
) {

    /** 导出结果：CSV 正文 + 数据行数（不含表头，供审计 detail 使用）。 */
    data class CsvExport(val body: String, val rowCount: Int)

    private fun encodeRow(row: List<Any?>): String =
        row.joinToString(",") { cell -> csvCell(cell) }

    private fun csv(header: String, rows: List<List<Any?>>): CsvExport = CsvExport(
        body = buildString {
            appendLine(header)
            rows.forEach { row -> appendLine(encodeRow(row)) }
        },
        rowCount = rows.size,
    )

    fun pushTokensCsv(limit: Int) = csv(
        "userId,deviceId,platform,tokenPrefix,timezoneOffsetMinutes,updatedAt",
        repository.pushTokens(limit),
    )

    fun usersCsv(limit: Int) = csv(
        "id,name,email,status,isOnline,isModerator,suspendedUntil,lastSeen",
        repository.users(limit),
    )

    fun botsCsv(limit: Int) = csv(
        "id,name,username,ownerUserId,tokenPrefix,webhookUrl,enabled,createdAt,updatedAt",
        repository.bots(limit),
    )

    fun moderationAuditCsv(limit: Int) = csv(
        "id,actorId,userId,action,detail,createdAt",
        repository.moderationAudit(limit),
    )

    fun botCommandStatsCsv(limit: Int) = csv(
        "id,botId,chatId,userId,command,createdAt",
        repository.botCommandStats(limit),
    )

    fun friendshipsCsv(limit: Int) = csv(
        "userLowId,userHighId,createdAt",
        repository.friendships(limit),
    )

    fun blockedUsersCsv(limit: Int) = csv(
        "blockerId,blockedId",
        repository.blockedUsers(limit),
    )

    fun reportsCsv(limit: Int) = csv(
        "id,reporterId,targetType,targetId,reason,status,createdAt",
        repository.reports(limit),
    )

    fun riskEventsCsv(limit: Int) = csv(
        "id,userId,source,action,matched,needsReview,createdAt",
        repository.riskEvents(limit),
    )

    fun sessionsSummaryCsv(limit: Int) = csv(
        "userId,name,isOnline,activeRefreshSessions,lastSeen",
        repository.sessionsSummary(limit),
    )

    /**
     * 消息统计导出。排序刻意放在**编码之后**：原实现是
     * `map { listOf(csvCell(t), csvCell(c)).joinToString(",") }.sorted()`，
     * 排的是编码后的整行字符串，不是原始 kind。为保持逐字一致，这里照搬。
     */
    fun messageStatsCsv(): CsvExport {
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

    fun pollsCsv(limit: Int) = csv(
        "id,chatId,creatorId,question,multi,anonymous,closed,voteRows,createdAt,closesAt",
        repository.polls(limit),
    )

    fun reportsMetaCsv(limit: Int) = csv(
        "id,reporterId,targetType,targetId,chatId,reason,status,actionTaken,createdAt",
        repository.reportsMeta(limit),
    )

    fun chatSettingsCsv(limit: Int) = csv(
        "userId,chatId,pinnedAt,notificationsMuted,archived,markedUnread,updatedAt",
        repository.chatSettings(limit),
    )

    fun disappearingChatsCsv(limit: Int) = csv(
        "chatId,isGroup,groupName,disappearingSeconds",
        repository.disappearingChats(limit),
    )

    fun mutedChatsCsv(limit: Int) = csv(
        "userId,chatId,notificationsMuted,updatedAt",
        repository.mutedChats(limit),
    )
}
