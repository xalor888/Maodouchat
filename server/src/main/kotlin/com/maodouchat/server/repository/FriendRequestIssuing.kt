package com.maodouchat.server.repository

// 好友请求发起取消簇：发送、取消（原 FriendRequestLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Users
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun FriendRepository.sendRequest(fromUserId: String, toUserId: String, message: String = ""): FriendRepository.Result {
    if (fromUserId == toUserId) return FriendRepository.Result.Failure("不能添加自己为好友", "SELF")
    if (!isValidUserId(fromUserId) || !isValidUserId(toUserId)) {
        return FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
    }
    if (message.trim().length > FriendRepository.MAX_MESSAGE_LEN) {
        return FriendRepository.Result.Failure("申请消息过长", "MESSAGE_TOO_LONG")
    }
    // PG：唯一冲突后当前事务 abort，嵌套 transaction{}（useNestedTransactions=false）复用
    // 同一连接救不回来——8.48 的「catch 内新事务回读」在 PG 上仍是死代码（并发双向申请必 500）。
    // 正确姿势：catch 在事务外（Exposed 已回滚归还连接），在全新事务回读已有 PENDING。
    return try {
        transaction {
            // 两个账号按固定顺序同时加锁，A→B 与 B→A 也会落到同一串行化锚点。
            val lockedUsers = lockUserPair(fromUserId, toUserId)
            val target = lockedUsers.firstOrNull { it[Users.id] == toUserId }
                ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
            val sender = lockedUsers.firstOrNull { it[Users.id] == fromUserId }
                ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
            if (sender[Users.deletedAt] != null) return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
            if (target[Users.deletedAt] != null) return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
            if (isBlockedEitherWay(fromUserId, toUserId)) {
                return@transaction FriendRepository.Result.Failure("无法发送好友申请", "BLOCKED")
            }
            if (areFriendsInTransaction(fromUserId, toUserId)) {
                return@transaction FriendRepository.Result.Failure("已经是好友", "ALREADY_FRIENDS")
            }
            // 接收方好友数上限保护：超限拒绝新申请，防止超大数据集的 fanout 资源耗尽。
            // 上限对主动添加方不生效（自己少好友不影响），只保护「好友很多的人」。
            if (friendCountInTransaction(toUserId) >= FriendRepository.MAX_FRIENDS_PER_USER) {
                return@transaction FriendRepository.Result.Failure("对方好友数量已达上限", "FRIEND_LIMIT_EXCEEDED")
            }
            val pending = FriendRequests.selectAll().where {
                (FriendRequests.status eq "PENDING") and (
                    ((FriendRequests.fromUserId eq fromUserId) and (FriendRequests.toUserId eq toUserId)) or
                        ((FriendRequests.fromUserId eq toUserId) and (FriendRequests.toUserId eq fromUserId))
                    )
            }.firstOrNull()
            if (pending != null) {
                return@transaction if (pending[FriendRequests.fromUserId] == fromUserId) {
                    FriendRepository.Result.Failure("已发送过申请，请等待对方处理", "ALREADY_PENDING")
                } else {
                    FriendRepository.Result.Failure("对方已向你发起申请，请在收件箱处理", "INCOMING_PENDING")
                }
            }
            val now = System.currentTimeMillis()
            val id = "fr_${UUID.randomUUID()}"
            val safeMessage = message.trim()
            FriendRequests.insert {
                it[FriendRequests.id] = id
                it[FriendRequests.fromUserId] = fromUserId
                it[FriendRequests.toUserId] = toUserId
                it[FriendRequests.message] = safeMessage
                it[status] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
            val created = loadRequest(id)
                ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
            FriendRepository.Result.Success(created)
        }
    } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
        // Postgres 部分唯一索引 uidx_friend_requests_pending 兜底：并发双向申请时
        // 后提交者冲突 → 回滚后在新事务回读返回已有 PENDING（B8 并发加固）。
        if (!isUniqueViolation(conflict)) throw conflict
        transaction {
            val existing = FriendRequests.selectAll().where {
                (FriendRequests.status eq "PENDING") and (
                    ((FriendRequests.fromUserId eq fromUserId) and (FriendRequests.toUserId eq toUserId)) or
                        ((FriendRequests.fromUserId eq toUserId) and (FriendRequests.toUserId eq fromUserId))
                    )
            }.firstOrNull()
            if (existing != null) {
                if (existing[FriendRequests.fromUserId] == fromUserId) {
                    FriendRepository.Result.Failure("已发送过申请，请等待对方处理", "ALREADY_PENDING")
                } else {
                    FriendRepository.Result.Failure("对方已向你发起申请，请在收件箱处理", "INCOMING_PENDING")
                }
            } else throw conflict
        }
    }
}


fun FriendRepository.cancelRequest(userId: String, requestId: String): FriendRepository.Result = transaction {
    if (!isValidRequestId(requestId)) return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
        ?: return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    if (row[FriendRequests.fromUserId] != userId) {
        return@transaction FriendRepository.Result.Failure("无权取消该申请", "FORBIDDEN")
    }
    if (row[FriendRequests.status] != "PENDING") {
        return@transaction FriendRepository.Result.Failure("申请已处理", "NOT_PENDING")
    }
    val now = System.currentTimeMillis()
    FriendRequests.update({ FriendRequests.id eq requestId }) {
        it[status] = "CANCELLED"
        it[updatedAt] = now
    }
    val cancelled = loadRequest(requestId)
        ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
    FriendRepository.Result.Success(cancelled)
}
