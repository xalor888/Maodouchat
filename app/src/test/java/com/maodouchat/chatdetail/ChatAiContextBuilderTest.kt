package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.AiContextSenders
import com.maodouchat.ui.screen.chatdetail.buildPlainAiContextMessages
import com.maodouchat.ui.screen.chatdetail.buildSummaryAiContextMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAiContextBuilderTest {
    private val senders = AiContextSenders(
        currentUserId = "me",
        currentUserLabel = "我",
        fallbackLabel = "对方",
        namesByUserId = mapOf("alice" to "Alice")
    )

    @Test
    fun `plain context is chronological bounded and excludes ai feedback`() {
        val messages = listOf(
            message("new", "alice", "new text", 30L),
            message("ai", "alice", "generated", 20L, MessageMeta(aiAssisted = true)),
            message("old", "me", "x".repeat(1_200), 10L),
            message("system", "alice", "system", 40L, type = MessageType.SYSTEM)
        )

        val context = buildPlainAiContextMessages(messages, senders, limit = 2)

        assertEquals(listOf("我", "Alice"), context.map { it.sender })
        assertEquals(1_000, context.first().text.length)
        assertEquals("new text", context.last().text)
        assertFalse(context.any { it.text == "generated" || it.text == "system" })
    }

    @Test
    fun `summary only includes text transcript and translations`() {
        val voice = message(
            "voice",
            "unknown",
            "encrypted-reference",
            20L,
            MessageMeta(
                voiceTranscript = "语音内容",
                translations = mapOf("中文" to "中文翻译", "English" to "translation"),
                aiImageAnalyses = mapOf("describe" to "model image result"),
                aiFileAnalyses = mapOf("summarize" to "model file result")
            ),
            MessageType.VOICE
        )
        val text = message("text", "me", "正文", 10L)

        val context = buildSummaryAiContextMessages(listOf(voice, text), senders)

        assertEquals(listOf("我", "对方"), context.map { it.sender })
        assertEquals("正文", context.first().text)
        assertTrue(context.last().text.contains("语音内容"))
        assertTrue(context.last().text.contains("中文翻译"))
        assertTrue(context.last().text.contains("translation"))
        assertFalse(context.last().text.contains("model image result"))
        assertFalse(context.last().text.contains("model file result"))
    }

    @Test
    fun `plain context keeps markdown but drops image messages`() {
        val messages = listOf(
            message("md", "alice", "# 标题", 10L, type = MessageType.MARKDOWN),
            message("img", "alice", "photo", 20L, type = MessageType.IMAGE),
            message("txt", "alice", "hello", 30L)
        )

        val context = buildPlainAiContextMessages(messages, senders, limit = 10)

        assertEquals(listOf("# 标题", "hello"), context.map { it.text })
    }

    @Test
    fun `plain context limit is clamped into the 1 to 60 range`() {
        val few = (1..5).map { message("m$it", "alice", "text $it", it.toLong()) }
        val many = (1..70).map { message("m$it", "alice", "text $it", it.toLong()) }

        assertEquals(1, buildPlainAiContextMessages(few, senders, limit = 0).size)
        assertEquals(60, buildPlainAiContextMessages(many, senders, limit = 500).size)
    }

    @Test
    fun `sender labels longer than 80 chars are cut down`() {
        val longNames = AiContextSenders(
            currentUserId = "me",
            currentUserLabel = "我",
            fallbackLabel = "对方",
            namesByUserId = mapOf("alice" to "A".repeat(200))
        )

        val context = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "hi", 1L)),
            longNames,
            limit = 10
        )

        assertEquals(80, context.single().sender.length)
    }

    @Test
    fun `summary honors limit and keeps chronological order`() {
        val messages = (1..6).map { message("m$it", "alice", "text $it", it.toLong()) }

        val context = buildSummaryAiContextMessages(messages, senders, limit = 3)

        assertEquals(listOf("text 4", "text 5", "text 6"), context.map { it.text })
    }

    @Test
    fun `empty message lists produce empty contexts`() {
        assertTrue(buildPlainAiContextMessages(emptyList(), senders, limit = 10).isEmpty())
        assertTrue(buildSummaryAiContextMessages(emptyList(), senders).isEmpty())
    }

    private fun message(
        id: String,
        senderId: String,
        content: String,
        timestamp: Long,
        meta: MessageMeta = MessageMeta(),
        type: MessageType = MessageType.TEXT
    ) = Message(id, "chat-1", senderId, content, type, timestamp, meta = meta)
}
