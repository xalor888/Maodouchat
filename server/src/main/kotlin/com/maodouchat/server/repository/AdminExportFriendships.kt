package com.maodouchat.server.repository

// 管理导出·社交关系簇：好友边与拉黑边元数据导出（原 AdminExportSocial 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Friendships
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 好友关系图元数据 only — no message bodies。 */
fun AdminExportRepository.friendships(limit: Int): List<List<Any?>> = transaction {
    Friendships.selectAll()
        .orderBy(
            Friendships.createdAt to SortOrder.DESC,
            Friendships.userLowId to SortOrder.DESC,
            Friendships.userHighId to SortOrder.DESC,
        )
        .limit(limit)
        .map { row ->
            listOf(
                row[Friendships.userLowId],
                row[Friendships.userHighId],
                row[Friendships.createdAt].toString(),
            )
        }
}

/** 只有拉黑边 — no message bodies。 */
fun AdminExportRepository.blockedUsers(limit: Int): List<List<Any?>> = transaction {
    BlockedUsers.selectAll()
        .limit(limit)
        .map { row ->
            listOf(
                row[BlockedUsers.blockerId],
                row[BlockedUsers.blockedId],
            )
        }
}
