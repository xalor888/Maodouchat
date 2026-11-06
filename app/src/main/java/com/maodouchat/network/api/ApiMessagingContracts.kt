package com.maodouchat.network.api

import com.maodouchat.network.*
import com.maodouchat.core.model.AcknowledgeEnvelopesResponseV2
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ConversationSnapshotV2Dto
import com.maodouchat.core.model.PendingInboxResponseV2
import com.maodouchat.core.model.SendMessageRequestV2
import com.maodouchat.core.model.SendMessageResponseV2
import com.maodouchat.core.model.UpdateChatSettingsRequest

interface MessagingApi {
    suspend fun sendMessageV2(
    token: String,
    request: SendMessageRequestV2,
): Result<SendMessageResponseV2>

    suspend fun getConversationSnapshotV2(
    token: String,
    conversationId: String,
): Result<ConversationSnapshotV2Dto>

    suspend fun getPendingInboxV2(
    token: String,
    limit: Int = 100,
): Result<PendingInboxResponseV2>

    suspend fun acknowledgeInboxV2(
    token: String,
    envelopeIds: List<String>,
): Result<AcknowledgeEnvelopesResponseV2>
}


interface ConversationApi {
    suspend fun getChats(token: String): Result<List<ChatDto>>

    suspend fun updateChatSettings(token: String, chatId: String, request: UpdateChatSettingsRequest): Result<ChatSettingsResponse>

    suspend fun updateDisappearingMessages(
    token: String,
    chatId: String,
    seconds: Int
): Result<DisappearingMessagesResponse>

    suspend fun createChat(
    token: String,
    participantIds: List<String>,
    isGroup: Boolean = false,
    groupName: String? = null,
    chatType: String? = null
): Result<ChatDto>
}
