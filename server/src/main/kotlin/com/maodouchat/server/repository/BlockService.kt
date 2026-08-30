package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 拉黑服务（B02「拆 BlockService」）：双向拉黑、好友关系清理、批量拉黑查询。 */
class BlockService {

    fun blockUser(blockerId: String, blockedId: String): Boolean {
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

    private fun orderedPair(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a

    private fun lockUserPair(a: String, b: String) = Users.selectAll()
        .where { Users.id inList listOf(a, b).distinct() }
        .orderBy(Users.id to SortOrder.ASC)
        .forUpdate()
        .toList()

    fun unblockUser(blockerId: String, blockedId: String) {
        transaction {
            lockUserPair(blockerId, blockedId)
            BlockedUsers.deleteWhere {
                (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId)
            }
        }
    }
    fun getBlockedUsers(userId: String): List<String> {
        return transaction { BlockedUsers.selectAll().where { BlockedUsers.blockerId eq userId }.map { it[BlockedUsers.blockedId] } }
    }

    fun hasBlocked(blockerId: String, blockedId: String): Boolean {
        return transaction {
            !BlockedUsers.selectAll()
                .where { (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId) }
                .empty()
        }
    }

    fun isBlockedEitherWay(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || a == b) return false
        // Single transaction/query keeps the two directions consistent. The previous two
        // separate hasBlocked calls could race and return a stale non-blocked result.
        return transaction {
            !BlockedUsers.selectAll()
                .where {
                    ((BlockedUsers.blockerId eq a) and (BlockedUsers.blockedId eq b)) or
                        ((BlockedUsers.blockerId eq b) and (BlockedUsers.blockedId eq a))
                }
                .empty()
        }
    }

    /**
     * 批量双向拉黑查询（8.30 性能优化 A1）：一次 SQL 返回 [targetIds] 中与 [viewerId]
     * 存在任一方向拉黑的集合。供群消息 fanout / push 收件人过滤使用，替代逐成员事务。
     */
    fun blockedEitherWayIdsInTx(viewerId: String, targetIds: List<String>): Set<String> {
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

    /**
     * 批量判断一个用户集合内是否已经存在任一方向的拉黑关系。
     * 供建群 / 建频道等入群前校验使用，避免成员对全排列 N+1 查询。
     */
    fun hasBlockedPairInTx(userIds: List<String>): Boolean {
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
}
