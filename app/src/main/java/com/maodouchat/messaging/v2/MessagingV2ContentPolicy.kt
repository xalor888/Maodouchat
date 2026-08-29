package com.maodouchat.messaging.v2

/** Binds untrusted transport metadata to the authenticated encrypted application payload. */
internal object MessagingV2ContentPolicy {
    fun accepts(kind: String, content: MessagingV2Content): Boolean {
        return when (kind) {
            KIND_DATA -> content.version in SUPPORTED_DATA_VERSIONS &&
                content.event == null &&
                content.type !in RESERVED_CONTROL_TYPES
            KIND_EVENT -> content.version == 1 &&
                content.type == TYPE_EVENT && content.event?.action in DOMAIN_EVENT_ACTIONS
            KIND_RECEIPT -> content.version == 1 &&
                content.type == TYPE_EVENT && content.event?.action in RECEIPT_ACTIONS
            KIND_KEY_REQUEST -> content.version == 1 && acceptsSenderKeyRequest(content)
            KIND_SERVICE -> content.version == 1 && acceptsService(content)
            else -> false
        }
    }

    private fun acceptsSenderKeyRequest(content: MessagingV2Content): Boolean =
        content.type == TYPE_SENDER_KEY_REQUEST &&
            content.event == null &&
            content.body.isEmpty() &&
            content.attachmentIds.isEmpty() &&
            content.attributes[ATTRIBUTE_REQUESTED_SENDER]?.isNotBlank() == true &&
            content.attributes[ATTRIBUTE_REQUESTED_SENDER]!!.length <= 50 &&
            content.attributes[ATTRIBUTE_FAILED_MESSAGE_ID]?.isNotBlank() == true &&
            content.attributes[ATTRIBUTE_FAILED_MESSAGE_ID]!!.length <= 100

    private fun acceptsService(content: MessagingV2Content): Boolean {
        val event = content.event
        if (event == null) return content.type !in RESERVED_CONTROL_TYPES
        if (content.type != TYPE_EVENT || event.targetMessageId.isBlank()) return false
        return when (event.action) {
            MessagingV2EventAction.EDIT ->
                event.content != null && event.editedAt != null && event.reactionEmoji == null && event.reactions.isEmpty()
            MessagingV2EventAction.DELETE ->
                event.content == null && event.editedAt == null && event.reactionEmoji == null && event.reactions.isEmpty()
            MessagingV2EventAction.REACTION_SET ->
                event.content == null && event.editedAt == null &&
                    !event.reactionEmoji.isNullOrBlank() && event.reactionEmoji.length <= 32 && event.reactions.isEmpty()
            else -> false
        }
    }

    private val DOMAIN_EVENT_ACTIONS = setOf(
        MessagingV2EventAction.EDIT,
        MessagingV2EventAction.REVOKE,
        MessagingV2EventAction.DELETE,
        MessagingV2EventAction.REACTION_SET,
        MessagingV2EventAction.REACTION_SNAPSHOT,
    )
    private val RECEIPT_ACTIONS = setOf(
        MessagingV2EventAction.DELIVERY_RECEIPT,
        MessagingV2EventAction.READ_RECEIPT,
    )
    private const val KIND_DATA = "DATA"
    private const val KIND_EVENT = "EVENT"
    private const val KIND_RECEIPT = "RECEIPT"
    private const val KIND_KEY_REQUEST = "KEY_REQUEST"
    private const val KIND_SERVICE = "SERVICE"
    private const val TYPE_EVENT = "EVENT"
    private const val TYPE_SENDER_KEY_REQUEST = "SENDER_KEY_REQUEST"
    private const val ATTRIBUTE_REQUESTED_SENDER = "requestedSenderUserId"
    private const val ATTRIBUTE_FAILED_MESSAGE_ID = "failedMessageId"
    private val SUPPORTED_DATA_VERSIONS = setOf(1, 2)
    private val RESERVED_CONTROL_TYPES = setOf(TYPE_EVENT, TYPE_SENDER_KEY_REQUEST, "SK_DIST")
}

internal object MessagingV2ServiceEnvelopePolicy {
    fun accepts(senderUserId: String, senderDeviceId: Int, ciphertextType: String): Boolean =
        senderDeviceId == SERVICE_DEVICE_ID &&
            ciphertextType == CIPHERTEXT_SERVICE &&
            (senderUserId.startsWith("bot_") || senderUserId == SYSTEM_SENDER_ID)

    private const val CIPHERTEXT_SERVICE = "SERVICE_PLAINTEXT"
    private const val SERVICE_DEVICE_ID = 0
    private const val SYSTEM_SENDER_ID = "system"
}
