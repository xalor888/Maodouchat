package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.FriendRequestResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class FriendRepository {

    sealed class Result {
        data class Success(val request: FriendRequestResponse) : Result()
        data class Failure(val message: String, val code: String = "FRIEND_ERROR") : Result()
    }

    fun sendRequest(fromUserId: String, toUserId: String, message: String = ""): Result = transaction {
        if (fromUserId == toUserId) return@transaction Result.Failure("不能添加自己为好友", "SELF")
        if (!isValidUserId(fromUserId) || !isValidUserId(toUserId)) {
            return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        }
        if (message.trim().length > MAX_MESSAGE_LEN) {
            return@transaction Result.Failure("申请消息过长", "MESSAGE_TOO_LONG")
        }
        // 两个账号按固定顺序同时加锁，A→B 与 B→A 也会落到同一串行化锚点。
        val lockedUsers = lockUserPair(fromUserId, toUserId)
        val target = lockedUsers.firstOrNull { it[Users.id] == toUserId }
            ?: return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        val sender = lockedUsers.firstOrNull { it[Users.id] == fromUserId }
            ?: return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        if (sender[Users.deletedAt] != null) return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        if (target[Users.deletedAt] != null) return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        if (isBlockedEitherWay(fromUserId, toUserId)) {
            return@transaction Result.Failure("无法发送好友申请", "BLOCKED")
        }
        if (areFriendsInTransaction(fromUserId, toUserId)) {
            return@transaction Result.Failure("已经是好友", "ALREADY_FRIENDS")
        }
        // 接收方好友数上限保护：超限拒绝新申请，防止超大数据集的 fanout 资源耗尽。
        // 上限对主动添加方不生效（自己少好友不影响），只保护「好友很多的人」。
        if (Friendships.selectAll().where {
                (Friendships.userLowId eq toUserId) or (Friendships.userHighId eq toUserId)
            }.count() >= MAX_FRIENDS_PER_USER
        ) {
            return@transaction Result.Failure("对方好友数量已达上限", "FRIEND_LIMIT_EXCEEDED")
        }
        val pending = FriendRequests.selectAll().where {
            (FriendRequests.status eq "PENDING") and (
                ((FriendRequests.fromUserId eq fromUserId) and (FriendRequests.toUserId eq toUserId)) or
                    ((FriendRequests.fromUserId eq toUserId) and (FriendRequests.toUserId eq fromUserId))
                )
        }.firstOrNull()
        if (pending != null) {
            return@transaction if (pending[FriendRequests.fromUserId] == fromUserId) {
                Result.Failure("已发送过申请，请等待对方处理", "ALREADY_PENDING")
            } else {
                Result.Failure("对方已向你发起申请，请在收件箱处理", "INCOMING_PENDING")
            }
        }
        val now = System.currentTimeMillis()
        val id = "fr_${UUID.randomUUID()}"
        val safeMessage = message.trim()
        try {
            FriendRequests.insert {
                it[FriendRequests.id] = id
                it[FriendRequests.fromUserId] = fromUserId
                it[FriendRequests.toUserId] = toUserId
                it[FriendRequests.message] = safeMessage
                it[status] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
        } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // Postgres 部分唯一索引 uidx_friend_requests_pending 兜底：并发双向申请时
            // 后提交者冲突 → 回读返回已有 PENDING（B8 并发加固）。
            // 8.48 修复：PG/H2 唯一约束冲突后当前事务已 abort，任何后续语句（含 SELECT）都报
            // "current transaction is aborted" → 此前在同一事务内回读是死代码，并发双向申请必 500。
            // 必须在「新事务」回读（与 PostRepository.likePost 同模式）。
            val existing = org.jetbrains.exposed.sql.transactions.transaction {
                FriendRequests.selectAll().where {
                    (FriendRequests.status eq "PENDING") and (
                        ((FriendRequests.fromUserId eq fromUserId) and (FriendRequests.toUserId eq toUserId)) or
                            ((FriendRequests.fromUserId eq toUserId) and (FriendRequests.toUserId eq fromUserId))
                        )
                }.firstOrNull()
            }
            if (existing != null) {
                return@transaction if (existing[FriendRequests.fromUserId] == fromUserId) {
                    Result.Failure("已发送过申请，请等待对方处理", "ALREADY_PENDING")
                } else {
                    Result.Failure("对方已向你发起申请，请在收件箱处理", "INCOMING_PENDING")
                }
            }
            throw conflict
        }
        Result.Success(loadRequest(id)!!)
    }

    fun acceptRequest(userId: String, requestId: String): Result = transaction {
        if (!isValidRequestId(requestId)) return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        val probe = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.firstOrNull()
            ?: return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        if (probe[FriendRequests.toUserId] != userId) {
            return@transaction Result.Failure("无权处理该申请", "FORBIDDEN")
        }
        val fromId = probe[FriendRequests.fromUserId]
        val toId = probe[FriendRequests.toUserId]
        // sendRequest 先锁用户再访问申请；accept 必须保持同一顺序。
        val lockedUsers = lockUserPair(fromId, toId)
        if (lockedUsers.size != 2 || lockedUsers.any { it[Users.deletedAt] != null }) {
            return@transaction Result.Failure("用户不存在", "USER_NOT_FOUND")
        }
        val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
            ?: return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        if (row[FriendRequests.toUserId] != userId ||
            row[FriendRequests.fromUserId] != fromId ||
            row[FriendRequests.toUserId] != toId
        ) {
            return@transaction Result.Failure("无权处理该申请", "FORBIDDEN")
        }
        if (row[FriendRequests.status] != "PENDING") {
            return@transaction Result.Failure("申请已处理", "NOT_PENDING")
        }
        if (isBlockedEitherWay(fromId, toId)) {
            return@transaction Result.Failure("存在屏蔽关系，无法成为好友", "BLOCKED")
        }
        // 8.40：受理时复查接收方好友上限——sendRequest 只检查发送时刻，请求积压期间
        // 接收方可能已通过其他路径累积到上限，接受后不可回滚
        if (Friendships.selectAll().where {
                (Friendships.userLowId eq toId) or (Friendships.userHighId eq toId)
            }.count() >= MAX_FRIENDS_PER_USER
        ) {
            return@transaction Result.Failure("对方好友数量已达上限", "FRIEND_LIMIT_EXCEEDED")
        }
        val now = System.currentTimeMillis()
        FriendRequests.update({ FriendRequests.id eq requestId }) {
            it[status] = "ACCEPTED"
            it[updatedAt] = now
        }
        ensureFriendship(fromId, toId, now)
        Result.Success(loadRequest(requestId)!!)
    }

    fun rejectRequest(userId: String, requestId: String): Result = transaction {
        if (!isValidRequestId(requestId)) return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        // BUG-3 fix: FOR UPDATE 防止并发 accept/reject 导致状态不一致
        val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
            ?: return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        if (row[FriendRequests.toUserId] != userId) {
            return@transaction Result.Failure("无权处理该申请", "FORBIDDEN")
        }
        if (row[FriendRequests.status] != "PENDING") {
            return@transaction Result.Failure("申请已处理", "NOT_PENDING")
        }
        val now = System.currentTimeMillis()
        FriendRequests.update({ FriendRequests.id eq requestId }) {
            it[status] = "REJECTED"
            it[updatedAt] = now
        }
        Result.Success(loadRequest(requestId)!!)
    }

    fun cancelRequest(userId: String, requestId: String): Result = transaction {
        if (!isValidRequestId(requestId)) return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
            ?: return@transaction Result.Failure("申请不存在", "NOT_FOUND")
        if (row[FriendRequests.fromUserId] != userId) {
            return@transaction Result.Failure("无权取消该申请", "FORBIDDEN")
        }
        if (row[FriendRequests.status] != "PENDING") {
            return@transaction Result.Failure("申请已处理", "NOT_PENDING")
        }
        val now = System.currentTimeMillis()
        FriendRequests.update({ FriendRequests.id eq requestId }) {
            it[status] = "CANCELLED"
            it[updatedAt] = now
        }
        Result.Success(loadRequest(requestId)!!)
    }

    fun listIncoming(userId: String, status: String = "PENDING", limit: Int = 50): List<FriendRequestResponse> =
        transaction {
            val st = status.trim().uppercase().ifBlank { "PENDING" }
            val blockedIds = blockedPeerIds(userId)
            val base = FriendRequests.selectAll()
                .where { (FriendRequests.toUserId eq userId) and (FriendRequests.status eq st) }
            val query = if (blockedIds.isEmpty()) base else base.andWhere { FriendRequests.fromUserId notInList blockedIds }
            val rows = query
                .orderBy(FriendRequests.createdAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .toList()
            mapRequestList(rows)
        }

    fun listOutgoing(userId: String, status: String = "PENDING", limit: Int = 50): List<FriendRequestResponse> =
        transaction {
            val st = status.trim().uppercase().ifBlank { "PENDING" }
            val blockedIds = blockedPeerIds(userId)
            val base = FriendRequests.selectAll()
                .where { (FriendRequests.fromUserId eq userId) and (FriendRequests.status eq st) }
            val query = if (blockedIds.isEmpty()) base else base.andWhere { FriendRequests.toUserId notInList blockedIds }
            val rows = query
                .orderBy(FriendRequests.createdAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .toList()
            mapRequestList(rows)
        }

    private fun blockedPeerIds(userId: String): Set<String> =
        BlockedUsers.selectAll()
            .where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { row ->
                val blocker = row[BlockedUsers.blockerId]
                val blocked = row[BlockedUsers.blockedId]
                if (blocker == userId) blocked else blocker
            }
            .toSet()

    // 8.48 修复 H1：批量映射——此前 mapRequest 逐行查 Users（limit 100 → 100 次查询）
    private fun mapRequestList(rows: List<ResultRow>): List<FriendRequestResponse> {
        if (rows.isEmpty()) return emptyList()
        val userIds = rows.flatMap { listOf(it[FriendRequests.fromUserId], it[FriendRequests.toUserId]) }.distinct()
        val userMap = Users.selectAll()
            .where { Users.id inList userIds }
            .associateBy { it[Users.id] }
        return rows.mapNotNull { mapRequest(it, userMap) }
    }

    fun listFriends(userId: String, limit: Int = 200): List<UserResponse> = transaction {
        val friendIds = Friendships.selectAll()
            .where { (Friendships.userLowId eq userId) or (Friendships.userHighId eq userId) }
            .map { row ->
                if (row[Friendships.userLowId] == userId) row[Friendships.userHighId]
                else row[Friendships.userLowId]
            }
        if (friendIds.isEmpty()) return@transaction emptyList()
        val blockedIds = BlockedUsers.selectAll()
            .where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { row ->
                val blocker = row[BlockedUsers.blockerId]
                val blocked = row[BlockedUsers.blockedId]
                if (blocker == userId) blocked else blocker
            }
            .toSet()
        val base = Users.selectAll()
            .where { (Users.id inList friendIds) and Users.deletedAt.isNull() }
        val query = if (blockedIds.isEmpty()) base else base.andWhere { Users.id notInList blockedIds }
        query.orderBy(Users.id to SortOrder.ASC)
            .limit(limit.coerceIn(1, 200))
            .mapNotNull { row ->
                UserResponse(
                    id = row[Users.id],
                    name = row[Users.name],
                    email = "",
                    avatar = row[Users.avatar],
                    status = if (row[Users.showStatus]) row[Users.status] else "",
                    isOnline = if (row[Users.showOnline]) row[Users.isOnline] else false,
                    isModerator = false
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun removeFriend(userId: String, friendId: String): Boolean = transaction {
        if (userId == friendId) return@transaction false
        if (lockUserPair(userId, friendId).size != 2) return@transaction false
        val (low, high) = orderedPair(userId, friendId)
        val deleted = Friendships.deleteWhere {
            (Friendships.userLowId eq low) and (Friendships.userHighId eq high)
        }
        deleted > 0
    }

    private fun areFriendsInTransaction(a: String, b: String): Boolean {
        if (a == b) return false
        val (low, high) = orderedPair(a, b)
        return Friendships.selectAll()
            .where { (Friendships.userLowId eq low) and (Friendships.userHighId eq high) }
            .firstOrNull() != null
    }

    private fun ensureFriendship(a: String, b: String, now: Long) {
        val (low, high) = orderedPair(a, b)
        val exists = Friendships.selectAll()
            .where { (Friendships.userLowId eq low) and (Friendships.userHighId eq high) }
            .firstOrNull() != null
        if (!exists) {
            Friendships.insert {
                it[userLowId] = low
                it[userHighId] = high
                it[createdAt] = now
            }
        }
    }

    private fun loadRequest(id: String): FriendRequestResponse? =
        FriendRequests.selectAll().where { FriendRequests.id eq id }.firstOrNull()?.let {
            mapRequest(it, emptyMap())
        }

    private fun mapRequest(row: ResultRow, userMap: Map<String, ResultRow>): FriendRequestResponse? {
        val fromId = row[FriendRequests.fromUserId]
        val toId = row[FriendRequests.toUserId]
        // 列表路径已由 mapRequestList 批量取回；单条路径（userMap 空）在此回查
        val userRows = if (userMap.isNotEmpty()) userMap else {
            Users.selectAll()
                .where { Users.id inList listOf(fromId, toId) }
                .associateBy { it[Users.id] }
        }
        val fromUser = userRows[fromId] ?: return null
        val toUser = userRows[toId] ?: return null
        fun publicUser(u: ResultRow) = UserResponse(
            id = u[Users.id],
            name = u[Users.name],
            email = "",
            avatar = u[Users.avatar],
            status = if (u[Users.showStatus]) u[Users.status] else "",
            isOnline = if (u[Users.showOnline]) u[Users.isOnline] else false,
            isModerator = false
        )
        return FriendRequestResponse(
            id = row[FriendRequests.id],
            fromUser = publicUser(fromUser),
            toUser = publicUser(toUser),
            message = row[FriendRequests.message],
            status = row[FriendRequests.status],
            createdAt = row[FriendRequests.createdAt],
            updatedAt = row[FriendRequests.updatedAt]
        )
    }

    private fun isBlockedEitherWay(a: String, b: String): Boolean =
        BlockedUsers.selectAll()
            .where {
                ((BlockedUsers.blockerId eq a) and (BlockedUsers.blockedId eq b)) or
                    ((BlockedUsers.blockerId eq b) and (BlockedUsers.blockedId eq a))
            }
            .firstOrNull() != null

    private fun orderedPair(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a

    private fun lockUserPair(a: String, b: String) =
        Users.selectAll()
            .where { Users.id inList listOf(a, b).distinct() }
            .orderBy(Users.id to SortOrder.ASC)
            .forUpdate()
            .toList()

    private fun isValidUserId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun isValidRequestId(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /**
     * 清理超过期限仍未处理的 PENDING 好友请求（默认 30 天），防止无限堆积。
     * 由 Routing.kt 的周期清理循环调用；超期未处理的请求对双方都无意义。
     */
    fun expireStalePending(days: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return transaction {
            FriendRequests.deleteWhere {
                (FriendRequests.status eq "PENDING") and (FriendRequests.createdAt less cutoff)
            }
        }
    }

    companion object {
        private const val MAX_MESSAGE_LEN = 300
        /** 单用户好友数上限（防超大好友集 fanout 资源耗尽）。 */
        const val MAX_FRIENDS_PER_USER = 2_000
    }
}
