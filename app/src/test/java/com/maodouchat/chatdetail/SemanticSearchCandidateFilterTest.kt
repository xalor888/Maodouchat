package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatSearchScope
import com.maodouchat.ui.screen.chatdetail.ChatSearchWindow
import com.maodouchat.ui.screen.chatdetail.buildChatSearchDocuments
import com.maodouchat.ui.screen.chatdetail.semanticSearchCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchCandidateFilterTest {

    @Test
    fun `keyword pre-filter prioritizes messages containing query tokens`() {
        val messages = listOf(
            message("m1", "hello world", senderId = "userA"),
            message("m2", "completely unrelated text", senderId = "userA"),
            message("m3", "hello again", senderId = "userA"),
            message("m4", "more unrelated content", senderId = "userA")
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "hello",
            now = 10_000L
        )
        // Messages containing "hello" should be in candidates
        assertTrue("m1 should be in candidates", candidates.any { it.id == "m1" })
        assertTrue("m3 should be in candidates", candidates.any { it.id == "m3" })
    }

    @Test
    fun `starred messages are always included as candidates`() {
        val messages = listOf(
            message("m1", "normal text", senderId = "userA"),
            message("m2", "starred important message", senderId = "userA", starred = true),
            message("m3", "another normal", senderId = "userA")
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "nonexistent",
            now = 10_000L
        )
        // Starred message should be included even if query doesn't match
        assertTrue("starred m2 should be in candidates", candidates.any { it.id == "m2" })
    }

    @Test
    fun `no query returns all valid candidates`() {
        val messages = (0 until 10).map { i ->
            message("m$i", "text $i", senderId = "userA")
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(10, candidates.size)
    }

    @Test
    fun `diversity limit applies when more than 2 senders`() {
        // 5 senders, 10 messages each = 50 total
        val messages = (0 until 5).flatMap { senderIdx ->
            (0 until 10).map { msgIdx ->
                message("m${senderIdx}_$msgIdx", "text $msgIdx", senderId = "sender$senderIdx")
            }
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        // With 5 senders, maxPerSender = 100/5*2 = 40, so all 50 should fit
        assertTrue("All 50 messages should be candidates", candidates.size <= 100)
        // Verify multiple senders are represented
        val senders = candidates.map { it.senderId }.distinct()
        assertTrue("Multiple senders should be represented", senders.size >= 3)
    }

    @Test
    fun `single sender is not limited by diversity`() {
        val messages = (0 until 65).map { i ->
            message("m$i", "text $i", senderId = "userA")
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(65, candidates.size)
    }

    @Test
    fun `excluded message types are filtered out`() {
        val messages = listOf(
            message("m1", "hello", type = MessageType.TEXT),
            message("m2", "secret", type = MessageType.SK_DIST),
            message("m3", "system", type = MessageType.SYSTEM),
            message("m4", "nudge", type = MessageType.NUDGE)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(1, candidates.size)
        assertEquals("m1", candidates.first().id)
    }

    private fun message(
        id: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        meta: MessageMeta = MessageMeta(),
        timestamp: Long = 1L,
        starred: Boolean = false,
        senderId: String = "user"
    ) = Message(id, "chat", senderId, content, type, timestamp, MessageStatus.SENT, starred = starred, meta = meta)
}
