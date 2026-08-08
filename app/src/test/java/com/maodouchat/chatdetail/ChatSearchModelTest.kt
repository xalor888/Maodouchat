package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.ChatSearchScope
import com.maodouchat.ui.screen.chatdetail.ChatSearchWindow
import com.maodouchat.ui.screen.chatdetail.buildChatSearchDocuments
import com.maodouchat.ui.screen.chatdetail.searchChatDocuments
import com.maodouchat.ui.screen.chatdetail.semanticSearchCandidates
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchModelTest {
    @Test
    fun `search reuses text voice and translation documents including Chinese compatibility key`() {
        val documents = buildChatSearchDocuments(
            listOf(
                message("text", MessageType.TEXT, "Release plan"),
                message("voice", MessageType.VOICE, "voice://1", MessageMeta(voiceTranscript = "周六发布")),
                message(
                    "translation",
                    MessageType.TEXT,
                    "hello",
                    MessageMeta(translations = mapOf("中文" to "你好世界", "English" to "hello world"))
                )
            )
        )

        assertEquals("text", searchChatDocuments(documents, "release", ChatSearchScope.TEXT, ChatSearchWindow.ALL).single().id)
        assertEquals("voice", searchChatDocuments(documents, "周六", ChatSearchScope.VOICE, ChatSearchWindow.ALL).single().id)
        assertEquals("translation", searchChatDocuments(documents, "你好", ChatSearchScope.TRANSLATION, ChatSearchWindow.ALL).single().id)
        assertEquals("你好世界", documents.single { it.message.id == "translation" }.translations.first())
    }

    @Test
    fun `starred blank query and time windows are deterministic`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val documents = buildChatSearchDocuments(
            listOf(
                message("old", MessageType.TEXT, "old", timestamp = now - 8L * 24L * 60L * 60L * 1_000L, starred = true),
                message("today", MessageType.TEXT, "today", timestamp = now - 1_000L, starred = true),
                message("plain", MessageType.TEXT, "plain", timestamp = now, starred = false)
            )
        )

        assertEquals(listOf("today", "old"), searchChatDocuments(documents, "", ChatSearchScope.STARRED, ChatSearchWindow.ALL, now).map { it.id })
        assertEquals(listOf("today"), searchChatDocuments(documents, "", ChatSearchScope.STARRED, ChatSearchWindow.TODAY, now).map { it.id })
        assertFalse(searchChatDocuments(documents, "old", ChatSearchScope.ALL, ChatSearchWindow.SEVEN_DAYS, now).any())
    }

    @Test
    fun `semantic candidates exclude protocol messages and keep latest sixty chronologically`() {
        val messages = (0 until 65).map { index -> message("m$index", MessageType.TEXT, "text $index", timestamp = index.toLong()) } +
            message("hidden", MessageType.SK_DIST, "secret", timestamp = 100L)
        val candidates = semanticSearchCandidates(
            buildChatSearchDocuments(messages),
            ChatSearchScope.ALL,
            ChatSearchWindow.ALL,
            now = 1_000L
        )

        assertEquals(65, candidates.size)
        assertEquals("m0", candidates.first().id)
        assertEquals("m64", candidates.last().id)
        assertTrue(candidates.none { it.id == "hidden" })
    }

    private fun message(
        id: String,
        type: MessageType,
        content: String,
        meta: MessageMeta = MessageMeta(),
        timestamp: Long = 1L,
        starred: Boolean = false
    ) = Message(id, "chat", "user", content, type, timestamp, MessageStatus.SENT, starred = starred, meta = meta)
}
