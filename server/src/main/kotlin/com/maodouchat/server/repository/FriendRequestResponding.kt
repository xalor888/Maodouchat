package com.maodouchat.server.repository

// 好友请求响应簇：接受、拒绝（原 FriendRequestLifecycle 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.FriendRequests
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun FriendRepository.acceptRequest(userId: String, requestId: String): FriendRepository.Result = transaction {
    if (!isValidRequestId(requestId)) return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    val probe = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.firstOrNull()
        ?: return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    if (probe[FriendRequests.toUserId] != userId) {
        return@transaction FriendRepository.Result.Failure("无权处理该申请", "FORBIDDEN")
    }
    val fromId = probe[FriendRequests.fromUserId]
    val toId = probe[FriendRequests.toUserId]
    // sendRequest 先锁用户再访问申请；accept 必须保持同一顺序。
    val lockedUsers = lockUserPair(fromId, toId)
    if (lockedUsers.size != 2 || lockedUsers.any { it[Users.deletedAt] != null }) {
        return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
    }
    val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
        ?: return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    if (row[FriendRequests.toUserId] != userId ||
        row[FriendRequests.fromUserId] != fromId ||
        row[FriendRequests.toUserId] != toId
    ) {
        return@transaction FriendRepository.Result.Failure("无权处理该申请", "FORBIDDEN")
    }
    if (row[FriendRequests.status] != "PENDING") {
        return@transaction FriendRepository.Result.Failure("申请已处理", "NOT_PENDING")
    }
    if (isBlockedEitherWay(fromId, toId)) {
        return@transaction FriendRepository.Result.Failure("存在屏蔽关系，无法成为好友", "BLOCKED")
    }
    // 8.40：受理时复查双方好友上限——sendRequest 只检查发送时刻，请求积压期间
    // 任一方可能已通过其他路径累积到上限，接受后不可回滚。
    // toId 是当前操作者（接收方），超限文案用「好友数量已达上限」；fromId 是申请方。
    if (friendCountInTransaction(toId) >= FriendRepository.MAX_FRIENDS_PER_USER) {
        return@transaction FriendRepository.Result.Failure("好友数量已达上限", "FRIEND_LIMIT_EXCEEDED")
    }
    if (friendCountInTransaction(fromId) >= FriendRepository.MAX_FRIENDS_PER_USER) {
        return@transaction FriendRepository.Result.Failure("对方好友数量已达上限", "FRIEND_LIMIT_EXCEEDED")
    }
    val now = System.currentTimeMillis()
    FriendRequests.update({ FriendRequests.id eq requestId }) {
        it[status] = "ACCEPTED"
        it[updatedAt] = now
    }
    ensureFriendship(fromId, toId, now)
    val accepted = loadRequest(requestId)
        ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
    FriendRepository.Result.Success(accepted)
}


fun FriendRepository.rejectRequest(userId: String, requestId: String): FriendRepository.Result = transaction {
    if (!isValidRequestId(requestId)) return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    // BUG-3 fix: FOR UPDATE 防止并发 accept/reject 导致状态不一致
    val row = FriendRequests.selectAll().where { FriendRequests.id eq requestId }.forUpdate().firstOrNull()
        ?: return@transaction FriendRepository.Result.Failure("申请不存在", "NOT_FOUND")
    if (row[FriendRequests.toUserId] != userId) {
        return@transaction FriendRepository.Result.Failure("无权处理该申请", "FORBIDDEN")
    }
    if (row[FriendRequests.status] != "PENDING") {
        return@transaction FriendRepository.Result.Failure("申请已处理", "NOT_PENDING")
    }
    val now = System.currentTimeMillis()
    FriendRequests.update({ FriendRequests.id eq requestId }) {
        it[status] = "REJECTED"
        it[updatedAt] = now
    }
    val rejected = loadRequest(requestId)
        ?: return@transaction FriendRepository.Result.Failure("用户不存在", "USER_NOT_FOUND")
    FriendRepository.Result.Success(rejected)
}
