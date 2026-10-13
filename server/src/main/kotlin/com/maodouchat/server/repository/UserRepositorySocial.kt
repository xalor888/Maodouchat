package com.maodouchat.server.repository

// 资料/用户名/拉黑社交簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.*
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.blockUser
import com.maodouchat.server.service.blockedEitherWayIdsInTx
import com.maodouchat.server.service.getBlockedUsers
import com.maodouchat.server.service.hasBlocked
import com.maodouchat.server.service.hasBlockedPairInTx
import com.maodouchat.server.service.isBlockedEitherWay
import com.maodouchat.server.service.toPublicUser
import com.maodouchat.server.service.unblockUser
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun UserRepository.updateProfile(userId: String, name: String? = null, status: String? = null) =
    profileService.updateProfile(userId, name, status)

fun UserRepository.replaceAvatar(userId: String, avatarUrl: String?): AvatarReplacementResult? =
    profileService.replaceAvatar(userId, avatarUrl)

fun UserRepository.isCurrentAvatarUrl(avatarUrl: String): Boolean =
    profileService.isCurrentAvatarUrl(avatarUrl)

fun UserRepository.findByUsername(username: String): UserResponse? =
    profileService.findByUsername(username)

fun UserRepository.setUsername(userId: String, username: String): String? =
    profileService.setUsername(userId, username)

fun UserRepository.clearUsername(userId: String): Boolean =
    profileService.clearUsername(userId)

fun UserRepository.blockUser(blockerId: String, blockedId: String): Boolean = blockService.blockUser(blockerId, blockedId)

fun UserRepository.unblockUser(blockerId: String, blockedId: String) = blockService.unblockUser(blockerId, blockedId)

fun UserRepository.getBlockedUsers(userId: String): List<String> = blockService.getBlockedUsers(userId)

fun UserRepository.hasBlocked(blockerId: String, blockedId: String): Boolean = blockService.hasBlocked(blockerId, blockedId)

fun UserRepository.isBlockedEitherWay(a: String, b: String): Boolean = blockService.isBlockedEitherWay(a, b)

fun UserRepository.blockedEitherWayIdsInTx(viewerId: String, targetIds: List<String>): Set<String> = blockService.blockedEitherWayIdsInTx(viewerId, targetIds)

fun UserRepository.hasBlockedPairInTx(userIds: List<String>): Boolean = blockService.hasBlockedPairInTx(userIds)

fun UserRepository.getBlockedUserDetails(userId: String): List<UserResponse> {
    return transaction {
        val blockedIds = BlockedUsers.selectAll()
            .where { BlockedUsers.blockerId eq userId }
            .map { it[BlockedUsers.blockedId] }
        if (blockedIds.isEmpty()) {
            emptyList()
        } else {
            Users.selectAll()
                .where { Users.id inList blockedIds }
                .map { it.toPublicUser() }
                .sortedBy { blockedIds.indexOf(it.id) }
        }
    }
}

fun UserRepository.isUniqueViolation(error: Throwable): Boolean =
    com.maodouchat.server.service.isUniqueViolation(error)
