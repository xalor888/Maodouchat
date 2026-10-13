package com.maodouchat.server.repository

// 管理导出·Bot 簇：Bot 应用元数据导出（原 AdminExportSocial 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BotApps
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 只导出元数据：token 只含前缀，绝不导出 tokenHash。 */
fun AdminExportRepository.bots(limit: Int): List<List<Any?>> = transaction {
    BotApps.selectAll()
        .orderBy(BotApps.createdAt to SortOrder.DESC)
        .limit(limit)
        .map { row ->
            listOf(
                row[BotApps.id],
                row[BotApps.name],
                row[BotApps.username],
                row[BotApps.ownerUserId],
                row[BotApps.tokenPrefix],
                row[BotApps.webhookUrl] ?: "",
                row[BotApps.enabled].toString(),
                row[BotApps.createdAt].toString(),
                row[BotApps.updatedAt].toString(),
            )
        }
}
