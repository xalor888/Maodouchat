package com.maodouchat.server.messaging.v2

import com.maodouchat.server.model.MessageResponse

data class MessagingV2MessageMetadata(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val recordClass: String,
)

data class MessagingV2ModerationDeleteResult(
    val metadata: MessagingV2MessageMetadata,
    val deletedAttachmentIds: List<String>,
)

/**
 * B06：Messaging V2 门面。准入、设备快照、邮箱、元数据与服务发布各自独立子域，
 * 本类仅组合依赖并保留原公开 API（Routing/Bot 无需感知拆分）。
 */
class MessagingV2Repository(
    deviceDirectory: com.maodouchat.server.repository.EncryptableDeviceDirectory =
        com.maodouchat.server.repository.DeviceRegistry(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshotStore = ConversationDeviceSnapshotStore(deviceDirectory)
    private val admission = MessageAdmissionPolicy(snapshotStore, clock)
    private val mailboxStore = EnvelopeMailboxStore(clock)
    private val metadataStore = MessageMetadataStore()
    private val servicePublisher = ServiceMessagePublisher(clock)

    fun resolveAuthenticatedDevice(userId: String, authSessionId: String): Int? =
        snapshotStore.resolveAuthenticatedDevice(userId, authSessionId)

    fun conversationSnapshot(
        conversationId: String,
        requesterUserId: String,
        requesterDeviceId: Int,
    ): ConversationSnapshotV2Response =
        snapshotStore.conversationSnapshot(conversationId, requesterUserId, requesterDeviceId)

    fun send(
        command: SendMessageV2Command,
        admitNewMessage: () -> Boolean = { true },
    ): SendMessageV2Result = admission.send(command, admitNewMessage)

    fun enqueueServiceMessage(
        message: MessageResponse,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result = servicePublisher.enqueueServiceMessage(message, recipientUserIds)

    internal fun enqueueServiceMessageInTransaction(
        message: MessageResponse,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result = servicePublisher.enqueueServiceMessageInTransaction(message, recipientUserIds)

    fun enqueueServiceEvent(
        id: String,
        conversationId: String,
        senderUserId: String,
        clientTimestamp: Long,
        event: ServiceMessagingV2Event,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result = servicePublisher.enqueueServiceEvent(id, conversationId, senderUserId, clientTimestamp, event, recipientUserIds)

    fun pending(userId: String, deviceId: Int, limit: Int): PendingInboxV2Response =
        mailboxStore.pending(userId, deviceId, limit)

    fun acknowledge(userId: String, deviceId: Int, envelopeIds: Set<String>): Int =
        mailboxStore.acknowledge(userId, deviceId, envelopeIds)

    fun messageMetadata(messageId: String): MessagingV2MessageMetadata? =
        metadataStore.messageMetadata(messageId)

    fun deleteMessageForModeration(messageId: String): MessagingV2ModerationDeleteResult? =
        metadataStore.deleteMessageForModeration(messageId)
}
