package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message

/** Serializes privacy-sensitive local effects shared by REST success and WebSocket authority. */
internal class MessageTerminalStore(
    private val deleteCachedMedia: (String) -> Unit,
    private val deleteSearchDocument: suspend (String) -> Unit,
    private val deleteLocalMessage: suspend (String) -> Unit,
    private val upsertLocalMessage: suspend (Message) -> Unit
) {
    suspend fun persistDeleted(messageId: String) {
        if (messageId.isBlank()) return
        runAll(
            { deleteCachedMedia(messageId) },
            { deleteSearchDocument(messageId) },
            { deleteLocalMessage(messageId) },
        )
    }

    suspend fun persistRevoked(messageId: String, revokedMessage: Message?) {
        if (messageId.isBlank()) return
        // Cache removal is mandatory even if Room no longer has enough data to render a placeholder.
        runAll(
            { deleteCachedMedia(messageId) },
            { deleteSearchDocument(messageId) },
            { if (revokedMessage != null) upsertLocalMessage(revokedMessage) },
        )
    }

    private suspend fun runAll(vararg operations: suspend () -> Unit) {
        var firstFailure: Exception? = null
        operations.forEach { operation ->
            try {
                operation()
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }
}
