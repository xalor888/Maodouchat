package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class ChatListGroupForwardModelsTest {

    @Test
    fun receiptHoldsStatus() {
        val receipt = Receipt(fromMe = true, status = MessageStatus.SENT)
        assertTrue(receipt.fromMe)
        assertEquals(MessageStatus.SENT, receipt.status)
        assertEquals(MessageStatus.READ, receipt.copy(status = MessageStatus.READ).status)
    }

    @Test
    fun chatListPreviewConstruction() {
        val preview = ChatListPreview(
            text = "hi",
            type = MessageType.TEXT,
            timestamp = 1_000L,
        )
        assertEquals("hi", preview.text)
        assertEquals(MessageType.TEXT, preview.type)
        assertEquals(1_000L, preview.timestamp)
    }

    @Test
    fun groupMutationCommitDefaults() {
        val commit = GroupMutationCommit()
        assertTrue(commit.committed)
        assertEquals(null, commit.refreshedChat)
        assertFalse(commit.hasPostCommitWarning)
    }

    @Test
    fun groupMutationCommitWarnsOnErrors() {
        val refreshFailed = GroupMutationCommit(
            refreshError = IllegalStateException("refresh"),
        )
        assertTrue(refreshFailed.hasPostCommitWarning)
        val reconcileFailed = GroupMutationCommit(
            reconciliationError = IllegalStateException("reconcile"),
        )
        assertTrue(reconcileFailed.hasPostCommitWarning)
    }

    @Test
    fun forwardBatchResultDefaults() {
        val result = ForwardBatchResult(forwardedCount = 2, failedCount = 1)
        assertEquals(2, result.forwardedCount)
        assertEquals(1, result.failedCount)
        assertEquals(null, result.firstError)
        assertFalse(result.attachmentFailed)
    }

    @Test
    fun forwardExceptionsCarryType() {
        val sessionError = ConversationForwardSessionException("forward_session_missing")
        assertTrue(sessionError is IllegalStateException)
        assertEquals("forward_session_missing", sessionError.message)
        val unsupported = ConversationForwardUnsupportedException(MessageType.VOICE)
        assertTrue(unsupported is IllegalArgumentException)
        assertTrue(unsupported.message!!.contains("forward_type_unsupported"))
        assertEquals(MessageType.VOICE, unsupported.messageType)
    }

    @Test
    fun nonRecoverableWebSocketNetworkError() {
        assertTrue(
            isNonRecoverableWebSocketNetworkError(SSLHandshakeException("cert")),
        )
        assertTrue(
            isNonRecoverableWebSocketNetworkError(
                IOException("wrap", SSLException("cause")),
            ),
        )
        assertFalse(isNonRecoverableWebSocketNetworkError(IOException("read timed out")))
        assertFalse(isNonRecoverableWebSocketNetworkError(ConnectException("refused")))
        assertFalse(
            isNonRecoverableWebSocketNetworkError(
                RuntimeException("plain", IOException("cause")),
            ),
        )
    }
}
