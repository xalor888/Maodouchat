package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.Friendships
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 好友关系：列表、删除、双向判定。 */
fun FriendRepository.listFriends(userId: String, limit: Int = FriendRepository.MAX_FRIENDS_PER_USER): List<UserResponse> = transaction {
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
        .limit(limit.coerceIn(1, FriendRepository.MAX_FRIENDS_PER_USER))
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

fun FriendRepository.removeFriend(userId: String, friendId: String): Boolean = transaction {
    if (userId == friendId) return@transaction false
    if (lockUserPair(userId, friendId).size != 2) return@transaction false
    val (low, high) = orderedPair(userId, friendId)
    val deleted = Friendships.deleteWhere {
        (Friendships.userLowId eq low) and (Friendships.userHighId eq high)
    }
    deleted > 0
}

fun FriendRepository.areFriends(a: String, b: String): Boolean = transaction { areFriendsInTransaction(a, b) }
