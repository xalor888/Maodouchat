package com.maodouchat.server.service

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 拉黑写操作：单向拉黑/解除，拉黑时顺带清理好友关系并取消双向待处理好友请求。
fun BlockService.blockUser(blockerId: String, blockedId: String): Boolean {
    if (blockerId.isBlank() || blockedId.isBlank() || blockerId == blockedId) return false
    return transaction {
        val users = lockUserPair(blockerId, blockedId)
        if (users.size != 2 || users.any { it[Users.deletedAt] != null }) return@transaction false
        val existing = BlockedUsers.selectAll().where {
            (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId)
        }
        if (existing.empty()) {
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = blockerId
                it[BlockedUsers.blockedId] = blockedId
            }
        }
        val (low, high) = orderedPair(blockerId, blockedId)
        Friendships.deleteWhere {
            (Friendships.userLowId eq low) and (Friendships.userHighId eq high)
        }
        val now = System.currentTimeMillis()
        FriendRequests.update({
            (FriendRequests.status eq "PENDING") and (
                ((FriendRequests.fromUserId eq blockerId) and (FriendRequests.toUserId eq blockedId)) or
                    ((FriendRequests.fromUserId eq blockedId) and (FriendRequests.toUserId eq blockerId))
                )
        }) {
            it[FriendRequests.status] = "CANCELLED"
            it[FriendRequests.updatedAt] = now
        }
        true
    }
}

fun BlockService.unblockUser(blockerId: String, blockedId: String) {
    transaction {
        lockUserPair(blockerId, blockedId)
        BlockedUsers.deleteWhere {
            (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId)
        }
    }
}

private fun BlockService.orderedPair(a: String, b: String): Pair<String, String> =
    if (a <= b) a to b else b to a

private fun BlockService.lockUserPair(a: String, b: String) = Users.selectAll()
    .where { Users.id inList listOf(a, b).distinct() }
    .orderBy(Users.id to SortOrder.ASC)
    .forUpdate()
    .toList()
