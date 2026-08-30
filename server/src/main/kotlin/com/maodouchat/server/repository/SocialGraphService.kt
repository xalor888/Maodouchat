package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Friendships
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

/**
 * B10：社交图查询。联系人 = 已建立好友 ∪ 完整 1:1 私聊对方（不含群成员）；
 * 拉黑集合复用 [ConversationVisibility.blockedUserIdsInTx]。
 */
object SocialGraphService {
    /** 联系人集合：好友 ∪ 1:1 聊天对方。 */
    fun contactIds(userId: String): Set<String> {
        val contactIds = Friendships.selectAll()
            .where { (Friendships.userLowId eq userId) or (Friendships.userHighId eq userId) }
            .mapTo(hashSetOf()) { row ->
                if (row[Friendships.userLowId] == userId) row[Friendships.userHighId]
                else row[Friendships.userLowId]
            }
        val chatIds = ChatParticipants
            .innerJoin(Chats)
            .select(ChatParticipants.chatId)
            .where { (ChatParticipants.userId eq userId) and (Chats.isGroup eq false) }
            .map { it[ChatParticipants.chatId] }
            .toSet()
        if (chatIds.isEmpty()) return contactIds
        // 一次 SQL 取回全部 1:1 聊天成员，替代逐 chat 查询
        ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList chatIds }
            .groupBy({ it[ChatParticipants.chatId] }, { it[ChatParticipants.userId] })
            .forEach { (_, members) ->
                if (members.size == 2 && userId in members) {
                    members.firstOrNull { it != userId }?.let { contactIds += it }
                }
            }
        return contactIds
    }

    /** 双向拉黑集合（当前用户视角）。 */
    fun blockedEitherWayUserIds(userId: String): Set<String> =
        ConversationVisibility.blockedUserIdsInTx(userId)
}
