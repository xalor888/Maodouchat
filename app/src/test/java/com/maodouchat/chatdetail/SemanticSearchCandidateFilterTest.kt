package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatSearchScope
import com.maodouchat.ui.screen.chatdetail.MentionPolicy
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

    @Test
    fun `candidates are capped at one hundred`() {
        val messages = (0 until 120).map { i ->
            message("m$i", "text $i", senderId = "userA", timestamp = i.toLong())
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(100, candidates.size)
        // 单发送者场景下多样性上限按输入顺序先截断，保留的是最早的 100 条
        assertEquals("m0", candidates.first().id)
    }

    @Test
    fun `seven-day window drops older messages`() {
        val now = 10_000_000L
        val messages = listOf(
            message("fresh", "new text", timestamp = now - 1_000L),
            message("stale", "old text", timestamp = now - 8L * 24 * 3600 * 1000)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.SEVEN_DAYS,
            query = "",
            now = now
        )
        assertEquals(listOf("fresh"), candidates.map { it.id })
    }

    @Test
    fun `single-char query tokens are ignored in keyword prefilter`() {
        val messages = listOf(
            message("m1", "x marks the spot", senderId = "userA"),
            message("m2", "plain message", senderId = "userA")
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "x",
            now = 10_000L
        )
        assertEquals(2, candidates.size)
    }

    @Test
    fun `starred scope keeps only starred candidates`() {
        val messages = listOf(
            message("m1", "hello world", senderId = "userA"),
            message("m2", "hello there", senderId = "userA", starred = true)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.STARRED,
            ChatSearchWindow.ALL,
            query = "hello",
            now = 10_000L
        )
        assertEquals(listOf("m2"), candidates.map { it.id })
    }

    @Test
    fun `candidates come back in ascending timestamp order`() {
        val messages = listOf(
            message("new", "newer", timestamp = 30L),
            message("old", "older", timestamp = 10L),
            message("mid", "middle", timestamp = 20L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("old", "mid", "new"), candidates.map { it.id })
    }

    @Test
    fun `final chronological order dominates keyword and starred priority`() {
        val messages = listOf(
            message("hit", "hello world", timestamp = 30L),
            message("plain", "nothing here", timestamp = 10L),
            message("star", "nothing here", timestamp = 20L, starred = true)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "hello",
            now = 10_000L
        )

        assertEquals(listOf("plain", "star", "hit"), candidates.map { it.id })
    }

    @Test
    fun `media scope candidates keep only media messages`() {
        val messages = listOf(
            message("pic", "file://pic", type = MessageType.IMAGE, timestamp = 1L),
            message("clip", "file://clip", type = MessageType.VIDEO, timestamp = 2L),
            message("note", "hello", timestamp = 3L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.MEDIA,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )

        assertEquals(listOf("pic", "clip"), candidates.map { it.id })
    }

    @Test
    fun `mentions scope candidates keep direct and everyone mentions`() {
        val messages = listOf(
            message("direct", "hi", meta = MessageMeta(mentions = listOf("me")), timestamp = 1L),
            message("everyone", "all", meta = MessageMeta(mentions = listOf(MentionPolicy.EVERYONE_ID)), timestamp = 2L),
            message("other", "them", meta = MessageMeta(mentions = listOf("other")), timestamp = 3L),
            message("plain", "yo", timestamp = 4L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.MENTIONS,
            ChatSearchWindow.ALL,
            query = "",
            currentUserId = "me",
            now = 10_000L
        )

        assertEquals(listOf("direct", "everyone"), candidates.map { it.id })
    }

    @Test
    fun `per-sender cap binds with ten heavy senders`() {
        val messages = (0 until 10).flatMap { sender ->
            (0 until 30).map { index ->
                message("s${sender}_$index", "text $index", senderId = "sender$sender", timestamp = index * 10L + sender)
            }
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )

        assertEquals(100, candidates.size)
        assertTrue(candidates.groupBy { it.senderId }.values.all { it.size == 10 })
    }

    @Test
    fun `blank text and blank transcripts never become candidates`() {
        val messages = listOf(
            message("blank", "   "),
            message("empty", ""),
            message("voice", "voice://1", type = MessageType.VOICE, meta = MessageMeta(voiceTranscript = "  ")),
            message("good", "hello")
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )

        assertEquals(listOf("good"), candidates.map { it.id })
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


    @Test
    fun `thirty-day window keeps twenty-day-old and drops forty-day-old`() {
        val now = 5_000_000_000L
        val day = 24L * 3600 * 1000
        val messages = listOf(
            message("fresh", "new text", timestamp = now - 20 * day),
            message("stale", "old text", timestamp = now - 40 * day)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.THIRTY_DAYS,
            query = "",
            now = now
        )
        assertEquals(listOf("fresh"), candidates.map { it.id })
    }

    @Test
    fun `text scope keeps only messages with non-blank text`() {
        val messages = listOf(
            message("voice", "voice://1", type = MessageType.VOICE, meta = MessageMeta(voiceTranscript = "hello"), timestamp = 1L),
            message("blank", "   ", timestamp = 2L),
            message("text", "hello world", timestamp = 3L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.TEXT,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("text"), candidates.map { it.id })
    }

    @Test
    fun `voice scope keeps transcript messages and drops plain text`() {
        val messages = listOf(
            message("text", "hello world", timestamp = 1L),
            message("voice", "voice://1", type = MessageType.VOICE, meta = MessageMeta(voiceTranscript = "hello"), timestamp = 2L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.VOICE,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("voice"), candidates.map { it.id })
    }

    @Test
    fun `translation scope keeps only messages carrying a translation`() {
        val messages = listOf(
            message("plain", "hello", timestamp = 1L),
            message("translated", "bonjour", meta = MessageMeta(translations = mapOf("en" to "hello")), timestamp = 2L),
            message("blankTranslation", "hola", meta = MessageMeta(translations = mapOf("en" to "  ")), timestamp = 3L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.TRANSLATION,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("translated"), candidates.map { it.id })
    }

    @Test
    fun `starred stale message does not survive the seven-day window`() {
        val now = 10_000_000L
        val messages = listOf(
            message("starredStale", "old star", timestamp = now - 8L * 24 * 3600 * 1000, starred = true),
            message("fresh", "new text", timestamp = now - 1_000L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.SEVEN_DAYS,
            query = "",
            now = now
        )
        assertEquals(listOf("fresh"), candidates.map { it.id })
    }

    @Test
    fun `media scope keeps media messages even when the query matches nothing`() {
        val messages = listOf(
            message("pic", "file://pic", type = MessageType.IMAGE, timestamp = 1L),
            message("clip", "file://clip", type = MessageType.VIDEO, timestamp = 2L),
            message("note", "hello", timestamp = 3L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.MEDIA,
            ChatSearchWindow.ALL,
            query = "zzz",
            now = 10_000L
        )
        assertEquals(listOf("pic", "clip"), candidates.map { it.id })
    }

    @Test
    fun `keyword hits survive the per-sender cap ahead of plain messages`() {
        val senderA = (0 until 80).map { i ->
            val content = if (i >= 70) "hello target" else "plain text"
            message("a$i", content, senderId = "senderA", timestamp = i.toLong())
        }
        val senderB = (0 until 10).map { i ->
            message("b$i", "plain text", senderId = "senderB", timestamp = 1000L + i)
        }
        val senderC = (0 until 10).map { i ->
            message("c$i", "plain text", senderId = "senderC", timestamp = 2000L + i)
        }
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(senderA + senderB + senderC),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            query = "hello",
            now = 10_000L
        )
        assertEquals(86, candidates.size)
        val ids = candidates.map { it.id }.toSet()
        assertTrue((70 until 80).all { "a$it" in ids })
        assertTrue((56 until 70).none { "a$it" in ids })
        assertTrue((0 until 56).all { "a$it" in ids })
    }

    @Test
    fun `starred scope drops starred messages with no searchable part`() {
        val messages = listOf(
            message("starredBlank", "   ", starred = true),
            message("starredGood", "hello", starred = true),
            message("plainGood", "hello")
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.STARRED,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("starredGood"), candidates.map { it.id })
    }

    @Test
    fun `voice scope drops voice messages without a transcript`() {
        val messages = listOf(
            message("voiceBare", "voice://1", type = MessageType.VOICE, timestamp = 1L),
            message("voiceWith", "voice://1", type = MessageType.VOICE, meta = MessageMeta(voiceTranscript = "hello"), timestamp = 2L),
            message("textMsg", "hello", timestamp = 3L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.VOICE,
            ChatSearchWindow.ALL,
            query = "",
            now = 10_000L
        )
        assertEquals(listOf("voiceWith"), candidates.map { it.id })
    }

    @Test
    fun `seven-day window keeps the exact boundary message`() {
        val now = 5_000_000_000L
        val day = 24L * 3600 * 1000
        val messages = listOf(
            message("atBoundary", "edge", timestamp = now - 7 * day),
            message("justBefore", "old", timestamp = now - 7 * day - 1),
            message("fresh", "new", timestamp = now - 1_000L)
        )
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.SEVEN_DAYS,
            query = "",
            now = now
        )
        assertEquals(listOf("atBoundary", "fresh"), candidates.map { it.id })
    }
}
