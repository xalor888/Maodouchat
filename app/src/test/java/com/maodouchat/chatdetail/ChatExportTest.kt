package com.maodouchat.chatdetail

import android.content.Context
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.ChatExport
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dummyContext: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        dummyContext = mockk(relaxed = true)
        every { dummyContext.cacheDir } returns cacheDir
    }

    @Test
    fun buildText_includesFormatVersionAndHeader() {
        val messages = listOf(
            Message(
                id = "m1",
                chatId = "c1",
                senderId = "me",
                content = "Hello world",
                type = MessageType.TEXT,
                timestamp = 1600000000000L
            ),
            Message(
                id = "m2",
                chatId = "c1",
                senderId = "friend",
                content = "file:///img.png",
                type = MessageType.IMAGE,
                timestamp = 1600000005000L
            ),
            Message(
                id = "m3",
                chatId = "c1",
                senderId = "me",
                content = "hidden",
                type = MessageType.REVOKED,
                timestamp = 1600000010000L
            ),
            Message(
                id = "m4",
                chatId = "c1",
                senderId = "sys",
                content = "system alert",
                type = MessageType.SYSTEM,
                timestamp = 1600000015000L
            )
        )

        val text = ChatExport.buildText(
            chatName = "Test Chat",
            ownerId = "me",
            resolveSenderName = { if (it == "friend") "Alice" else it },
            messages = messages,
            exportedAt = 1600000100000L
        )

        assertTrue("Should include chat name", text.contains("=== Test Chat ==="))
        assertTrue("Should specify format version", text.contains("format_version=${ChatExport.FORMAT_VERSION}"))
        assertTrue("Should include exported timestamp", text.contains("exported_at="))
        assertTrue("Should display '我' for owner", text.contains("我: Hello world"))
        assertTrue("Should display Alice for friend", text.contains("Alice: [图片]"))
        assertTrue("Should format revoked message", text.contains("我: [消息已撤回]"))
        assertFalse("Should skip SYSTEM message", text.contains("system alert"))
    }

    @Test
    fun writeStream_writesFormattedFile() {
        val messages = listOf(
            Message(
                id = "m1",
                chatId = "c1",
                senderId = "me",
                content = "Stream message 1",
                type = MessageType.TEXT,
                timestamp = 1600000000000L
            ),
            Message(
                id = "m2",
                chatId = "c1",
                senderId = "friend",
                content = "Stream message 2",
                type = MessageType.TEXT,
                timestamp = 1600000001000L
            )
        )

        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "chat_123",
            chatName = "Stream Chat",
            ownerId = "me",
            messages = messages.asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000002000L
        )

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals("chat_123.txt", file.name)

        val content = file.readText()
        assertTrue(content.contains("format_version=${ChatExport.FORMAT_VERSION}"))
        assertTrue(content.contains("Stream message 1"))
        assertTrue(content.contains("Stream message 2"))
    }

    @Test
    fun writeStream_cancelledMidStream_cleansUpAndReturnsNull() {
        val messages = (1..50).map { i ->
            Message(
                id = "m$i",
                chatId = "c1",
                senderId = "user$i",
                content = "Message #$i",
                type = MessageType.TEXT,
                timestamp = 1600000000000L + i * 1000
            )
        }

        var messageCounter = 0
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "cancelled_export",
            chatName = "Cancelled Chat",
            ownerId = "me",
            messages = messages.asSequence().onEach { messageCounter++ },
            resolveSenderName = { it },
            isCancelled = { messageCounter >= 5 }
        )

        assertNull("Cancelled export should return null", file)

        val exportsDir = File(cacheDir, "exports")
        val targetFile = File(exportsDir, "cancelled_export.txt")
        val tempFile = File(exportsDir, "cancelled_export.tmp")

        assertFalse("Final file should not exist on cancellation", targetFile.exists())
        assertFalse("Temp file should be cleaned up on cancellation", tempFile.exists())
    }

    @Test
    fun renderBody_coversAllMessageTypes() {
        val meta = MessageMeta(fileName = "presentation.pdf")
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE, meta = meta)
        assertEquals("[文件] presentation.pdf", ChatExport.renderBody(fileMsg))

        val voiceMsg = Message(id = "2", chatId = "c", senderId = "u", content = "", type = MessageType.VOICE)
        assertEquals("[语音]", ChatExport.renderBody(voiceMsg))

        val locMsg = Message(id = "3", chatId = "c", senderId = "u", content = "", type = MessageType.LOCATION)
        assertEquals("[位置]", ChatExport.renderBody(locMsg))

        val nudgeMsg = Message(id = "4", chatId = "c", senderId = "u", content = "", type = MessageType.NUDGE)
        assertEquals("[戳一戳]", ChatExport.renderBody(nudgeMsg))
    }

    @Test
    fun renderBody_coversMarkdownStickerVideoGif() {
        val mdMsg = Message(id = "1", chatId = "c", senderId = "u", content = "**加粗**", type = MessageType.MARKDOWN)
        assertEquals("**加粗**", ChatExport.renderBody(mdMsg))

        val stickerMsg = Message(id = "2", chatId = "c", senderId = "u", content = "", type = MessageType.STICKER)
        assertEquals("[表情]", ChatExport.renderBody(stickerMsg))

        val videoMsg = Message(id = "3", chatId = "c", senderId = "u", content = "", type = MessageType.VIDEO)
        assertEquals("[视频]", ChatExport.renderBody(videoMsg))

        val gifMsg = Message(id = "4", chatId = "c", senderId = "u", content = "content://cached/a.gif", type = MessageType.GIF)
        assertEquals("content://cached/a.gif", ChatExport.renderBody(gifMsg))
    }

    @Test
    fun buildText_blankChatNameRendersDash() {
        val text = ChatExport.buildText(
            chatName = "",
            ownerId = "me",
            resolveSenderName = { it },
            messages = emptyList(),
            exportedAt = 1600000100000L
        )
        assertTrue("Should render dash for blank chat name", text.contains("=== - ==="))
    }

    @Test
    fun buildText_skipsSkDistAndEmptyBodyMessages() {
        val messages = listOf(
            Message(id = "d", chatId = "c", senderId = "me", content = "dist-payload", type = MessageType.SK_DIST, timestamp = 1600000000000L),
            Message(id = "e", chatId = "c", senderId = "me", content = "", type = MessageType.TEXT, timestamp = 1600000001000L)
        )
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = messages,
            exportedAt = 1600000100000L
        )
        assertFalse("Should skip SK_DIST", text.contains("dist-payload"))
        assertTrue("Only header lines for fully skipped body", !text.contains("[2020-09-13"))
    }

    @Test
    fun write_blankTextReturnsNull() {
        assertNull(ChatExport.write(dummyContext, "whatever", "   "))
    }

    @Test
    fun write_sanitizesUnsafeCharsAndTruncatesLongFileName() {
        val file = ChatExport.write(dummyContext, "a/b:c", "hello")
        assertNotNull(file)
        assertEquals("a_b_c.txt", file!!.name)
        assertEquals("hello", file.readText())

        val long = ChatExport.write(dummyContext, "x".repeat(100), "hi")
        assertNotNull(long)
        assertEquals(84, long!!.name.length)
    }

    @Test
    fun buildText_senderBlankNameFallsBackToId() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { "" },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("friend: hi"))
    }

    @Test
    fun buildText_countIncludesSkippedMessages() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "s", chatId = "c", senderId = "sys", content = "system alert", type = MessageType.SYSTEM, timestamp = 1600000000000L),
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000001000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("count=2"))
        assertFalse(text.contains("system alert"))
    }

    @Test
    fun writeStream_skipsSystemMessages() {
        val messages = listOf(
            Message(id = "s", chatId = "c", senderId = "sys", content = "system alert", type = MessageType.SYSTEM, timestamp = 1600000000000L),
            Message(id = "m1", chatId = "c", senderId = "me", content = "kept", type = MessageType.TEXT, timestamp = 1600000001000L)
        )
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "skip_system",
            chatName = "C",
            ownerId = "me",
            messages = messages.asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(file)
        val content = file!!.readText()
        assertTrue(content.contains("kept"))
        assertFalse(content.contains("system alert"))
    }

    @Test
    fun writeStream_usesDashForBlankChatName() {
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "blank_name",
            chatName = "",
            ownerId = "me",
            messages = emptySequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(file)
        assertTrue(file!!.readText().contains("=== - ==="))
    }

    @Test
    fun renderBody_fileWithoutNameRendersBareTag() {
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE)
        assertEquals("[文件]", ChatExport.renderBody(fileMsg))
    }


    @Test
    fun renderBody_systemAndSkDistRenderEmpty() {
        val sysMsg = Message(id = "1", chatId = "c", senderId = "u", content = "alert", type = MessageType.SYSTEM)
        assertEquals("", ChatExport.renderBody(sysMsg))
        val distMsg = Message(id = "2", chatId = "c", senderId = "u", content = "payload", type = MessageType.SK_DIST)
        assertEquals("", ChatExport.renderBody(distMsg))
    }

    @Test
    fun buildText_preservesInputOrderNotTimestampOrder() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "laterFirst", type = MessageType.TEXT, timestamp = 1600000003000L),
                Message(id = "m2", chatId = "c", senderId = "me", content = "earlierSecond", type = MessageType.TEXT, timestamp = 1600000001000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.indexOf("laterFirst") < text.indexOf("earlierSecond"))
    }

    @Test
    fun writeStream_immediateCancellationReturnsNullAndLeavesNoFiles() {
        val messages = listOf(
            Message(id = "m1", chatId = "c1", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
        )
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "immediate_cancel",
            chatName = "C",
            ownerId = "me",
            messages = messages.asSequence(),
            resolveSenderName = { it },
            isCancelled = { true }
        )
        assertNull(file)
        val exportsDir = File(cacheDir, "exports")
        assertFalse(File(exportsDir, "immediate_cancel.txt").exists())
        assertFalse(File(exportsDir, "immediate_cancel.tmp").exists())
    }

    @Test
    fun write_allUnsafeCharsSanitized() {
        val file = ChatExport.write(dummyContext, "***", "hi")
        assertNotNull(file)
        assertEquals("___.txt", file!!.name)
        assertEquals("hi", file.readText())
    }

    @Test
    fun buildText_revokedBodyRendersForNonOwner() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { if (it == "friend") "Alice" else it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "gone", type = MessageType.REVOKED, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("Alice: [\u6d88\u606f\u5df2\u64a4\u56de]"))
    }

    @Test
    fun buildText_textWithEmptyBodySkippedButCounted() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("count=1"))
        assertTrue(text.lines().none { it.startsWith("[") })
    }

    @Test
    fun renderBody_textReturnsContentVerbatim() {
        val msg = Message(id = "1", chatId = "c", senderId = "u", content = "**bold** text", type = MessageType.TEXT)
        assertEquals("**bold** text", ChatExport.renderBody(msg))
    }

    @Test
    fun writeStream_headerHasNoCountLine() {
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "no_count",
            chatName = "C",
            ownerId = "me",
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "kept", type = MessageType.TEXT, timestamp = 1600000000000L)
            ).asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(file)
        val content = file!!.readText()
        assertTrue(content.contains("kept"))
        assertTrue(content.contains("format_version=1"))
        assertFalse(content.contains("count="))
    }

    @Test
    fun write_emptyFileNameProducesTxtOnly() {
        val file = ChatExport.write(dummyContext, "", "hi")
        assertNotNull(file)
        assertEquals(".txt", file!!.name)
        assertEquals("hi", file.readText())
    }

    @Test
    fun buildText_gifRendersRawContent() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "content://cached/a.gif", type = MessageType.GIF, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("我: content://cached/a.gif"))
        assertFalse(text.contains("[图片]"))
    }

    @Test
    fun buildText_ownerAlwaysShowsWoEvenWhenResolverRenamesOwner() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { "Boss" },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("我: hi"))
        assertFalse(text.contains("Boss"))
    }

    @Test
    fun buildText_fileMessageRendersFileNameInBody() {
        val meta = MessageMeta(fileName = "deck.pdf")
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "friend", content = "", type = MessageType.FILE, meta = meta, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains("friend: [文件] deck.pdf"))
    }

    @Test
    fun write_emptyStringTextReturnsNull() {
        assertNull(ChatExport.write(dummyContext, "x", ""))
    }

    @Test
    fun write_fileNameWithQuestionAndQuotesSanitized() {
        val file = ChatExport.write(dummyContext, "a?b\"c", "hi")
        assertNotNull(file)
        assertEquals("a_b_c.txt", file!!.name)
    }

    @Test
    fun renderBody_revokedRendersTagRegardlessOfContent() {
        val msg = Message(id = "1", chatId = "c", senderId = "u", content = "gone", type = MessageType.REVOKED)
        assertEquals("[消息已撤回]", ChatExport.renderBody(msg))
    }

    @Test
    fun buildText_timestampsUseDateTimeFormats() {
        val text = ChatExport.buildText(
            chatName = "C",
            ownerId = "me",
            resolveSenderName = { it },
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "hi", type = MessageType.TEXT, timestamp = 1600000000000L)
            ),
            exportedAt = 1600000100000L
        )
        assertTrue(text.contains(Regex("exported_at=\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
        assertTrue(text.contains(Regex("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\] 我: hi")))
    }

    @Test
    fun writeStream_secondWriteOverwritesFirstFile() {
        val first = ChatExport.writeStream(
            context = dummyContext,
            fileName = "overwrite",
            chatName = "C",
            ownerId = "me",
            messages = listOf(
                Message(id = "m1", chatId = "c", senderId = "me", content = "first", type = MessageType.TEXT, timestamp = 1600000000000L)
            ).asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(first)
        val second = ChatExport.writeStream(
            context = dummyContext,
            fileName = "overwrite",
            chatName = "C",
            ownerId = "me",
            messages = listOf(
                Message(id = "m2", chatId = "c", senderId = "me", content = "second", type = MessageType.TEXT, timestamp = 1600000001000L)
            ).asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(second)
        val content = second!!.readText()
        assertTrue(content.contains("second"))
        assertFalse(content.contains("first"))
    }

    @Test
    fun write_exactlyEightyCharFileNameKeepsFullLength() {
        val file = ChatExport.write(dummyContext, "x".repeat(80), "hi")
        assertNotNull(file)
        assertEquals("x".repeat(80) + ".txt", file!!.name)
        assertEquals(84, file.name.length)
    }

    @Test
    fun renderBody_fileWithEmptyNameRendersTagWithTrailingSpace() {
        val fileMsg = Message(id = "1", chatId = "c", senderId = "u", content = "", type = MessageType.FILE, meta = MessageMeta(fileName = ""))
        assertEquals("[文件] ", ChatExport.renderBody(fileMsg))
    }

    @Test
    fun writeStream_emptySequenceProducesHeaderOnlyFile() {
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "header_only",
            chatName = "C",
            ownerId = "me",
            messages = emptySequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(file)
        val content = file!!.readText()
        assertTrue(content.contains("=== C ==="))
        assertTrue(content.contains("format_version=1"))
        assertFalse(content.contains("count="))
        assertTrue(content.lines().none { it.startsWith("[") })
    }
}
