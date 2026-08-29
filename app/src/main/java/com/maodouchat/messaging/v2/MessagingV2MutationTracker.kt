package com.maodouchat.messaging.v2

internal enum class MessageMutationKind { DELETE, REVOKE, EDIT }

internal data class MessageMutationTicket(
    val messageId: String,
    val kind: MessageMutationKind,
    val generation: Long,
)

/** Single-flight and authoritative-terminal tracking for optimistic message mutations. */
internal class MessageMutationTracker {
    private var generation = 0L
    private val pending = mutableMapOf<String, MessageMutationTicket>()
    private val deleted = mutableSetOf<String>()
    private val revoked = mutableSetOf<String>()

    @Synchronized
    fun begin(messageId: String, kind: MessageMutationKind): MessageMutationTicket? {
        if (messageId.isBlank() || messageId in deleted || pending.containsKey(messageId)) return null
        return MessageMutationTicket(messageId, kind, ++generation).also { pending[messageId] = it }
    }

    @Synchronized
    fun shouldRollback(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return ticket.messageId !in deleted && ticket.messageId !in revoked
    }

    @Synchronized
    fun complete(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return true
    }

    @Synchronized
    fun observeAuthoritative(messageId: String, kind: MessageMutationKind) {
        pending.remove(messageId)
        when (kind) {
            MessageMutationKind.DELETE -> {
                deleted += messageId
                revoked -= messageId
            }
            MessageMutationKind.REVOKE -> if (messageId !in deleted) revoked += messageId
            MessageMutationKind.EDIT -> Unit
        }
    }

    @Synchronized
    fun shouldDrop(messageId: String): Boolean =
        messageId in deleted || pending[messageId]?.kind == MessageMutationKind.DELETE

    @Synchronized
    fun shouldRenderRevoked(messageId: String): Boolean =
        messageId in revoked || pending[messageId]?.kind == MessageMutationKind.REVOKE
}
