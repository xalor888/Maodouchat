package com.maodouchat.server.repository

import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.model.FriendRequestResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 好友请求查询：收件箱、发件箱。 */
fun FriendRepository.listIncoming(userId: String, status: String = "PENDING", limit: Int = 50): List<FriendRequestResponse> =
    transaction {
        val st = status.trim().uppercase().ifBlank { "PENDING" }
        val blockedIds = blockedPeerIds(userId)
        val base = FriendRequests.selectAll()
            .where { (FriendRequests.toUserId eq userId) and (FriendRequests.status eq st) }
        val query = if (blockedIds.isEmpty()) base else base.andWhere { FriendRequests.fromUserId notInList blockedIds }
        val rows = query
            .orderBy(FriendRequests.createdAt to SortOrder.DESC, FriendRequests.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        mapRequestList(rows)
    }

fun FriendRepository.listOutgoing(userId: String, status: String = "PENDING", limit: Int = 50): List<FriendRequestResponse> =
    transaction {
        val st = status.trim().uppercase().ifBlank { "PENDING" }
        val blockedIds = blockedPeerIds(userId)
        val base = FriendRequests.selectAll()
            .where { (FriendRequests.fromUserId eq userId) and (FriendRequests.status eq st) }
        val query = if (blockedIds.isEmpty()) base else base.andWhere { FriendRequests.toUserId notInList blockedIds }
        val rows = query
            .orderBy(FriendRequests.createdAt to SortOrder.DESC, FriendRequests.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .toList()
        mapRequestList(rows)
    }
