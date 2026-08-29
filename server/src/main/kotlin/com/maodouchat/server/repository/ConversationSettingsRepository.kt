package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.model.ChatSettingsResponse
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.DisappearingMessagesResponse
import com.maodouchat.server.model.UpdateChatSettingsRequest
import com.maodouchat.server.service.DisappearingMessagePolicy
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

enum class ConversationSettingsMutationResult {
    UPDATED,
    CHAT_NOT_FOUND,
    NOT_PARTICIPANT,
    GROUP_NOT_SUPPORTED,
    INVALID_TIMER,
}

data class ChatSettingsMutationOutcome(
    val result: ConversationSettingsMutationResult,
    val settings: ChatSettingsResponse? = null,
)

data class DisappearingMessagesMutationOutcome(
    val result: ConversationSettingsMutationResult,
    val settings: DisappearingMessagesResponse? = null,
)

/** Per-user conversation preferences and shared 1:1 disappearing-message policy. */
class ConversationSettingsRepository {
    fun updateUserSettings(
        chatId: String,
        userId: String,
        request: UpdateChatSettingsRequest,
    ): ChatSettingsMutationOutcome = transaction {
        Chats.select(Chats.id).where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction ChatSettingsMutationOutcome(ConversationSettingsMutationResult.CHAT_NOT_FOUND)
        if (!isParticipantInTx(chatId, userId)) {
            return@transaction ChatSettingsMutationOutcome(ConversationSettingsMutationResult.NOT_PARTICIPANT)
        }
        val existing = ChatUserSettings.selectAll().where {
            (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId)
        }.firstOrNull()
        val now = System.currentTimeMillis()
        val pinnedAt = when (request.pinned) {
            true -> existing?.get(ChatUserSettings.pinnedAt)?.takeIf { it > 0 } ?: now
            false -> 0L
            null -> existing?.get(ChatUserSettings.pinnedAt) ?: 0L
        }
        val muted = request.notificationsMuted
            ?: existing?.get(ChatUserSettings.notificationsMuted)
            ?: false
        val archived = request.archived ?: existing?.get(ChatUserSettings.archived) ?: false
        val markedUnread = request.markedUnread ?: existing?.get(ChatUserSettings.markedUnread) ?: false
        if (existing == null) {
            ChatUserSettings.insert {
                it[ChatUserSettings.chatId] = chatId
                it[ChatUserSettings.userId] = userId
                it[ChatUserSettings.pinnedAt] = pinnedAt
                it[ChatUserSettings.notificationsMuted] = muted
                it[ChatUserSettings.archived] = archived
                it[ChatUserSettings.markedUnread] = markedUnread
                it[ChatUserSettings.updatedAt] = now
            }
        } else {
            ChatUserSettings.update({
                (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId)
            }) {
                it[ChatUserSettings.pinnedAt] = pinnedAt
                it[ChatUserSettings.notificationsMuted] = muted
                it[ChatUserSettings.archived] = archived
                it[ChatUserSettings.markedUnread] = markedUnread
                it[ChatUserSettings.updatedAt] = now
            }
        }
        ChatSettingsMutationOutcome(
            ConversationSettingsMutationResult.UPDATED,
            ChatSettingsResponse(chatId, pinnedAt, muted, archived, markedUnread, now),
        )
    }

    fun setDisappearingMessages(
        chatId: String,
        actorId: String,
        requestedSeconds: Int,
    ): DisappearingMessagesMutationOutcome = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction DisappearingMessagesMutationOutcome(
                ConversationSettingsMutationResult.CHAT_NOT_FOUND,
            )
        if (!isParticipantInTx(chatId, actorId)) {
            return@transaction DisappearingMessagesMutationOutcome(
                ConversationSettingsMutationResult.NOT_PARTICIPANT,
            )
        }
        if (chat[Chats.isGroup]) {
            return@transaction DisappearingMessagesMutationOutcome(
                ConversationSettingsMutationResult.GROUP_NOT_SUPPORTED,
            )
        }
        val seconds = if (chat[Chats.chatType] == ChatType.SECRET) {
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS
        } else {
            if (!DisappearingMessagePolicy.isAllowedSeconds(requestedSeconds)) {
                return@transaction DisappearingMessagesMutationOutcome(
                    ConversationSettingsMutationResult.INVALID_TIMER,
                )
            }
            requestedSeconds
        }
        Chats.update({ Chats.id eq chatId }) {
            it[disappearingMessageSeconds] = seconds
        }
        DisappearingMessagesMutationOutcome(
            ConversationSettingsMutationResult.UPDATED,
            DisappearingMessagesResponse(chatId, seconds, System.currentTimeMillis()),
        )
    }

    fun areNotificationsMuted(chatId: String, userId: String): Boolean = transaction {
        ChatUserSettings.select(ChatUserSettings.notificationsMuted)
            .where {
                (ChatUserSettings.chatId eq chatId) and
                    (ChatUserSettings.userId eq userId)
            }
            .firstOrNull()
            ?.get(ChatUserSettings.notificationsMuted)
            ?: false
    }

    fun mutedUserIds(chatId: String, userIds: List<String>): Set<String> {
        if (userIds.isEmpty()) return emptySet()
        return transaction {
            ChatUserSettings.select(ChatUserSettings.userId)
                .where {
                    (ChatUserSettings.chatId eq chatId) and
                        (ChatUserSettings.userId inList userIds.distinct()) and
                        (ChatUserSettings.notificationsMuted eq true)
                }
                .mapTo(linkedSetOf()) { it[ChatUserSettings.userId] }
        }
    }

    private fun isParticipantInTx(chatId: String, userId: String): Boolean =
        ChatParticipants.select(ChatParticipants.userId).where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.any()
}
