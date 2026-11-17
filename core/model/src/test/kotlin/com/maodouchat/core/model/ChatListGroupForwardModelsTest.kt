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
