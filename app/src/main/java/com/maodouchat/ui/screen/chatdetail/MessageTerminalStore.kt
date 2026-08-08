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
        deleteCachedMedia(messageId)
        deleteSearchDocument(messageId)
        deleteLocalMessage(messageId)
    }

    suspend fun persistRevoked(messageId: String, revokedMessage: Message?) {
        if (messageId.isBlank()) return
        // Cache removal is mandatory even if Room no longer has enough data to render a placeholder.
        deleteCachedMedia(messageId)
        deleteSearchDocument(messageId)
        if (revokedMessage != null) upsertLocalMessage(revokedMessage)
    }
}
