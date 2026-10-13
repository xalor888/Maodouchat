package com.maodouchat.server.service

import com.maodouchat.server.db.BlockedUsers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 拉黑读操作：单查、双向查、批量查。
fun BlockService.getBlockedUsers(userId: String): List<String> {
    return transaction { BlockedUsers.selectAll().where { BlockedUsers.blockerId eq userId }.map { it[BlockedUsers.blockedId] } }
}

fun BlockService.hasBlocked(blockerId: String, blockedId: String): Boolean {
    return transaction {
        !BlockedUsers.selectAll()
            .where { (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId) }
            .empty()
    }
}

fun BlockService.isBlockedEitherWay(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank() || a == b) return false
    // 双向一次查：之前两次 hasBlocked 分开查有竞态窗口，会返回过期结果。
    return transaction {
        !BlockedUsers.selectAll()
            .where {
                ((BlockedUsers.blockerId eq a) and (BlockedUsers.blockedId eq b)) or
                    ((BlockedUsers.blockerId eq b) and (BlockedUsers.blockedId eq a))
            }
            .empty()
    }
}

// 批量双向拉黑查询：一次 SQL 返回 targetIds 中与 viewerId 任一方向拉黑的集合，供群消息 fanout / push 收件人过滤用。
fun BlockService.blockedEitherWayIdsInTx(viewerId: String, targetIds: List<String>): Set<String> {
    if (viewerId.isBlank() || targetIds.isEmpty()) return emptySet()
    return transaction {
        val blockedByViewer = BlockedUsers.select(BlockedUsers.blockedId)
            .where { (BlockedUsers.blockerId eq viewerId) and (BlockedUsers.blockedId inList targetIds) }
            .map { it[BlockedUsers.blockedId] }
            .toSet()
        val blockedViewer = BlockedUsers.select(BlockedUsers.blockerId)
            .where { (BlockedUsers.blockedId eq viewerId) and (BlockedUsers.blockerId inList targetIds) }
            .map { it[BlockedUsers.blockerId] }
            .toSet()
        blockedByViewer + blockedViewer
    }
}

// 批量判断用户集合内是否存在任一方向拉黑，供建群/建频道入群前校验，避免 N+1。
fun BlockService.hasBlockedPairInTx(userIds: List<String>): Boolean {
    val distinct = userIds.distinct()
    if (distinct.size < 2) return false
    return transaction {
        !BlockedUsers.selectAll()
            .where {
                (BlockedUsers.blockerId inList distinct) and
                    (BlockedUsers.blockedId inList distinct) and
                    (BlockedUsers.blockerId neq BlockedUsers.blockedId)
            }
            .limit(1)
            .empty()
    }
}
