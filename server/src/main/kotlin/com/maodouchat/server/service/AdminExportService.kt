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

    private fun csv(header: String, rows: List<List<Any?>>): CsvExport = CsvExport(
        body = buildString {
            appendLine(header)
            rows.forEach { row -> appendLine(row.joinToString(",") { cell -> csvCell(cell) }) }
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
}
