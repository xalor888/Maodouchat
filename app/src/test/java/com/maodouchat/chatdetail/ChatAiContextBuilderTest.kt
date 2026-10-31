package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.AiContextSenders
import com.maodouchat.ui.screen.chatdetail.MAX_AI_SUMMARY_MESSAGES
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

    @Test
    fun `plain context orders same-timestamp messages by id`() {
        val messages = listOf(
            message("m-b", "alice", "b text", 10L),
            message("m-a", "alice", "a text", 10L)
        )

        val context = buildPlainAiContextMessages(messages, senders, limit = 10)

        assertEquals(listOf("a text", "b text"), context.map { it.text })
    }

    @Test
    fun `plain context honors a custom maxTextLength`() {
        val messages = listOf(message("m1", "alice", "x".repeat(500), 1L))

        val context = buildPlainAiContextMessages(messages, senders, limit = 10, maxTextLength = 50)

        assertEquals(50, context.single().text.length)
    }

    @Test
    fun `summary dedupes identical transcript and translation lines`() {
        val voice = message(
            "voice",
            "alice",
            "encrypted-reference",
            20L,
            MessageMeta(
                voiceTranscript = "语音内容",
                translations = mapOf("中文" to "语音内容")
            ),
            MessageType.VOICE
        )

        val context = buildSummaryAiContextMessages(listOf(voice), senders)

        assertEquals("语音内容", context.single().text)
    }

    @Test
    fun `blank-only messages produce empty contexts`() {
        val messages = listOf(message("m1", "alice", "   ", 1L))

        assertTrue(buildPlainAiContextMessages(messages, senders, limit = 10).isEmpty())
        assertTrue(buildSummaryAiContextMessages(messages, senders).isEmpty())
    }

    @Test
    fun `summary default limit caps at MAX_AI_SUMMARY_MESSAGES`() {
        val messages = (1..60).map { message("m$it", "alice", "text $it", it.toLong()) }

        val context = buildSummaryAiContextMessages(messages, senders)

        assertEquals(MAX_AI_SUMMARY_MESSAGES, context.size)
        assertEquals("text ${60 - MAX_AI_SUMMARY_MESSAGES + 1}", context.first().text)
        assertEquals("text 60", context.last().text)
    }

    @Test
    fun `blank mapped sender name falls back to the fallback label`() {
        val blankNames = senders.copy(namesByUserId = mapOf("alice" to "   "))

        val context = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "hi", 1L)),
            blankNames,
            limit = 10
        )

        assertEquals("对方", context.single().sender)
    }

    private fun message(
        id: String,
        senderId: String,
        content: String,
        timestamp: Long,
        meta: MessageMeta = MessageMeta(),
        type: MessageType = MessageType.TEXT
    ) = Message(id, "chat-1", senderId, content, type, timestamp, meta = meta)

    @Test
    fun `summary drops ai-assisted messages too`() {
        val assisted = message("ai", "alice", "generated", 10L, MessageMeta(aiAssisted = true))
        assertTrue(buildSummaryAiContextMessages(listOf(assisted), senders).isEmpty())
    }

    @Test
    fun `plain context takeLast keeps only the latest messages`() {
        val messages = (1..3).map { message("m$it", "alice", "text $it", it.toLong()) }
        val context = buildPlainAiContextMessages(messages, senders, limit = 2)
        assertEquals(listOf("text 2", "text 3"), context.map { it.text })
    }

    @Test
    fun `summary limit is clamped into range`() {
        val few = (1..3).map { message("m$it", "alice", "text $it", it.toLong()) }
        assertEquals(1, buildSummaryAiContextMessages(few, senders, limit = 0).size)
        assertEquals("text 3", buildSummaryAiContextMessages(few, senders, limit = 0).single().text)
        val many = (1..70).map { message("m$it", "alice", "text $it", it.toLong()) }
        val clamped = buildSummaryAiContextMessages(many, senders, limit = 500)
        assertEquals(MAX_AI_SUMMARY_MESSAGES, clamped.size)
        assertEquals("text ${70 - MAX_AI_SUMMARY_MESSAGES + 1}", clamped.first().text)
        assertEquals("text 70", clamped.last().text)
    }

    @Test
    fun `summary keeps image messages only through translations`() {
        val bare = message("img", "alice", "photo", 10L, type = MessageType.IMAGE)
        assertTrue(buildSummaryAiContextMessages(listOf(bare), senders).isEmpty())
        val translated = message(
            "img2", "alice", "photo", 20L,
            MessageMeta(translations = mapOf("en" to "photo translation")),
            MessageType.IMAGE
        )
        assertEquals(
            "photo translation",
            buildSummaryAiContextMessages(listOf(translated), senders).single().text
        )
    }

    @Test
    fun `summary orders same-timestamp messages by id`() {
        val messages = listOf(
            message("m-b", "alice", "b text", 10L),
            message("m-a", "alice", "a text", 10L)
        )
        val context = buildSummaryAiContextMessages(messages, senders)
        assertEquals(listOf("a text", "b text"), context.map { it.text })
    }

    @Test
    fun `plain context drops voice messages even when a transcript exists`() {
        val voice = message(
            "voice", "alice", "encrypted-reference", 10L,
            MessageMeta(voiceTranscript = "语音内容"), MessageType.VOICE
        )

        assertTrue(buildPlainAiContextMessages(listOf(voice), senders, limit = 10).isEmpty())
        assertEquals(
            "语音内容",
            buildSummaryAiContextMessages(listOf(voice), senders).single().text
        )
    }

    @Test
    fun `current user label wins over the names map`() {
        val renamed = senders.copy(namesByUserId = mapOf("me" to "冒名", "alice" to "Alice"))

        val context = buildPlainAiContextMessages(
            listOf(message("m1", "me", "hi", 1L)),
            renamed,
            limit = 10
        )

        assertEquals("我", context.single().sender)
    }

    @Test
    fun `plain context falls back for unmapped senders`() {
        val context = buildPlainAiContextMessages(
            listOf(message("m1", "stranger", "hi", 1L)),
            senders,
            limit = 10
        )

        assertEquals("对方", context.single().sender)
    }

    @Test
    fun `summary keeps markdown text`() {
        val context = buildSummaryAiContextMessages(
            listOf(message("md", "alice", "# 标题", 10L, type = MessageType.MARKDOWN)),
            senders
        )

        assertEquals("# 标题", context.single().text)
    }

    @Test
    fun `summary keeps system messages only through translations`() {
        val bare = message("sys", "alice", "system", 10L, type = MessageType.SYSTEM)
        assertTrue(buildSummaryAiContextMessages(listOf(bare), senders).isEmpty())

        val translated = message(
            "sys2", "alice", "system", 20L,
            MessageMeta(translations = mapOf("en" to "system translation")),
            MessageType.SYSTEM
        )
        assertEquals(
            "system translation",
            buildSummaryAiContextMessages(listOf(translated), senders).single().text
        )
    }

    @Test
    fun `plain context drops system messages even when they carry translations`() {
        val system = message(
            "sys", "alice", "system", 10L,
            MessageMeta(translations = mapOf("en" to "system translation")),
            MessageType.SYSTEM
        )

        assertTrue(buildPlainAiContextMessages(listOf(system), senders, limit = 10).isEmpty())
    }

    @Test
    fun `plain context cuts an 81-char sender label to 80`() {
        val longNames = senders.copy(namesByUserId = mapOf("alice" to "A".repeat(81)))

        val context = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "hi", 1L)),
            longNames,
            limit = 10
        )

        assertEquals(80, context.single().sender.length)
    }

    @Test
    fun `summary joins text content and its translation on separate lines`() {
        val text = message(
            "t1", "alice", "正文", 10L,
            MessageMeta(translations = mapOf("en" to "译文"))
        )

        val context = buildSummaryAiContextMessages(listOf(text), senders)

        assertEquals("正文\n译文", context.single().text)
    }

    @Test
    fun `summary keeps only the translation when the transcript is blank`() {
        val voice = message(
            "voice", "alice", "encrypted-reference", 10L,
            MessageMeta(
                voiceTranscript = "   ",
                translations = mapOf("en" to "voice translation")
            ),
            MessageType.VOICE
        )

        val context = buildSummaryAiContextMessages(listOf(voice), senders)

        assertEquals("voice translation", context.single().text)
    }

    @Test
    fun `plain context negative limit keeps only the latest message`() {
        val messages = (1..3).map { message("m$it", "alice", "text $it", it.toLong()) }

        val context = buildPlainAiContextMessages(messages, senders, limit = -5)

        assertEquals(listOf("text 3"), context.map { it.text })
    }

    @Test
    fun `plain context limit counts only kept messages not filtered out images`() {
        val messages = listOf(
            message("t1", "alice", "text1", 10L),
            message("img", "alice", "photo", 20L, type = MessageType.IMAGE),
            message("t2", "alice", "text2", 30L),
            message("t3", "alice", "text3", 40L)
        )

        val context = buildPlainAiContextMessages(messages, senders, limit = 2)

        assertEquals(listOf("text2", "text3"), context.map { it.text })
    }

    @Test
    fun `summary drops voice with blank transcript and blank translations`() {
        val voice = message(
            "voice", "alice", "encrypted-reference", 20L,
            MessageMeta(
                voiceTranscript = "   ",
                translations = mapOf("en" to "   ")
            ),
            MessageType.VOICE
        )

        assertTrue(buildSummaryAiContextMessages(listOf(voice), senders).isEmpty())
    }

    @Test
    fun `plain context max text length clamps to 1 and to 4000`() {
        val short = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "hello", 1L)),
            senders, limit = 10, maxTextLength = 0
        )
        assertEquals("h", short.single().text)

        val long = buildPlainAiContextMessages(
            listOf(message("m2", "alice", "x".repeat(4_500), 2L)),
            senders, limit = 10, maxTextLength = 5_000
        )
        assertEquals(4_000, long.single().text.length)
    }

    @Test
    fun `summary collapses identical translation values under different keys`() {
        val text = message(
            "t1", "alice", "正文", 10L,
            MessageMeta(translations = mapOf("en" to "same", "fr" to "same"))
        )

        val context = buildSummaryAiContextMessages(listOf(text), senders)

        assertEquals("正文\nsame", context.single().text)
    }

    @Test
    fun `plain context keeps an exactly 80 char sender label unchanged`() {
        val exactNames = senders.copy(namesByUserId = mapOf("alice" to "A".repeat(80)))

        val context = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "hi", 1L)),
            exactNames,
            limit = 10
        )

        assertEquals(80, context.single().sender.length)
    }

    @Test
    fun `plain context sorts equal timestamps by id`() {
        val messages = listOf(
            message("b", "alice", "second", 10L),
            message("a", "alice", "first", 10L)
        )

        val context = buildPlainAiContextMessages(messages, senders, limit = 10)

        assertEquals(listOf("first", "second"), context.map { it.text })
    }

    @Test
    fun `plain context limit zero keeps only the latest message`() {
        val messages = (1..3).map { message("m$it", "alice", "text $it", it.toLong()) }

        val context = buildPlainAiContextMessages(messages, senders, limit = 0)

        assertEquals(listOf("text 3"), context.map { it.text })
    }

    @Test
    fun `plain context limit above sixty keeps sixty latest`() {
        val messages = (1..70).map { message("m$it", "alice", "text $it", it.toLong()) }

        val context = buildPlainAiContextMessages(messages, senders, limit = 100)

        assertEquals(60, context.size)
        assertEquals("text 11", context.first().text)
        assertEquals("text 70", context.last().text)
    }

    @Test
    fun `summary includes markdown text`() {
        val md = message("md", "alice", "# heading", 10L, type = MessageType.MARKDOWN)

        val context = buildSummaryAiContextMessages(listOf(md), senders)

        assertEquals(listOf("# heading"), context.map { it.text })
    }

    @Test
    fun `summary drops blank transcript but keeps translations`() {
        val voice = message(
            "voice", "alice", "encrypted-reference", 20L,
            MessageMeta(
                voiceTranscript = "   ",
                translations = mapOf("en" to "hello")
            ),
            MessageType.VOICE
        )

        val context = buildSummaryAiContextMessages(listOf(voice), senders)

        assertEquals(listOf("hello"), context.map { it.text })
    }

    @Test
    fun `plain context trims surrounding whitespace from kept text`() {
        val context = buildPlainAiContextMessages(
            listOf(message("m1", "alice", "  hello  ", 1L)),
            senders,
            limit = 10
        )

        assertEquals("hello", context.single().text)
    }

    @Test
    fun `summary trims surrounding whitespace from text`() {
        val context = buildSummaryAiContextMessages(
            listOf(message("m1", "alice", "  hello  ", 1L)),
            senders
        )

        assertEquals("hello", context.single().text)
    }

    @Test
    fun `summary joins text transcript and translation in that order`() {
        val text = message(
            "m1", "alice", "正文", 10L,
            MessageMeta(
                voiceTranscript = "spoken",
                translations = mapOf("en" to "译文")
            )
        )

        val context = buildSummaryAiContextMessages(listOf(text), senders)

        assertEquals("正文\nspoken\n译文", context.single().text)
    }

    @Test
    fun `plain context drops location messages even when a transcript exists`() {
        val location = message(
            "loc", "alice", "place", 10L,
            MessageMeta(voiceTranscript = "spoken"),
            MessageType.LOCATION
        )

        assertTrue(buildPlainAiContextMessages(listOf(location), senders, limit = 10).isEmpty())
        assertEquals(
            "spoken",
            buildSummaryAiContextMessages(listOf(location), senders).single().text
        )
    }

    @Test
    fun `summary drops a translation that equals the text`() {
        val text = message(
            "m1", "alice", "same", 10L,
            MessageMeta(translations = mapOf("en" to "same"))
        )

        val context = buildSummaryAiContextMessages(listOf(text), senders)

        assertEquals("same", context.single().text)
    }
}
