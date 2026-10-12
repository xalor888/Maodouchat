package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.GroupInvitations
import com.maodouchat.server.db.Users
import java.security.SecureRandom
import java.util.Base64
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

internal const val STATUS_PENDING = "PENDING"
internal const val STATUS_ACCEPTED = "ACCEPTED"
internal const val STATUS_DECLINED = "DECLINED"
internal const val STATUS_CANCELLED = "CANCELLED"
internal val INVITE_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{32,80}$")
internal val SECURE_RANDOM = SecureRandom()

/** Durable invitation approval and invite-link state machine. */
class GroupInvitationRepository {
    internal fun hasBlockedCandidate(existingIds: Set<String>, candidates: List<String>): Boolean {
        if (existingIds.isEmpty() || candidates.isEmpty()) return false
        val involved = existingIds + candidates
        val pairs = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
            .where {
                (BlockedUsers.blockerId inList involved) and
                    (BlockedUsers.blockedId inList involved)
            }
            .mapTo(hashSetOf()) { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
        return candidates.any { candidate ->
            existingIds.any { memberId ->
                (memberId to candidate) in pairs || (candidate to memberId) in pairs
            }
        }
    }

    internal fun lockUsers(userIds: List<String>): List<ResultRow> {
        val ordered = userIds.distinct().sorted()
        if (ordered.isEmpty()) return emptyList()
        return Users.selectAll()
            .where { Users.id inList ordered }
            .orderBy(Users.id, SortOrder.ASC)
            .forUpdate()
            .toList()
    }

    internal fun markInvite(inviteId: String, status: String, now: Long = System.currentTimeMillis()) {
        GroupInvitations.update({ GroupInvitations.id eq inviteId }) {
            it[GroupInvitations.status] = status
            it[updatedAt] = now
        }
    }

    internal fun generateToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
