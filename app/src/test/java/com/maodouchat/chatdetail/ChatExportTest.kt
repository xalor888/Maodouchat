package com.maodouchat.chatdetail

import android.content.Context
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
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


    @Test
    fun write_sanitizesBackslashPipeAndStar() {
        val file = ChatExport.write(dummyContext, "a\\b|c*d", "hi")
        assertNotNull(file)
        assertEquals("a_b_c_d.txt", file!!.name)
        assertEquals("hi", file.readText())
    }

    @Test
    fun writeStream_skipsSkDistMessages() {
        val messages = listOf(
            Message(id = "d", chatId = "c", senderId = "me", content = "dist-payload", type = MessageType.SK_DIST, timestamp = 1600000000000L),
            Message(id = "m1", chatId = "c", senderId = "me", content = "kept", type = MessageType.TEXT, timestamp = 1600000001000L)
        )
        val file = ChatExport.writeStream(
            context = dummyContext,
            fileName = "skip_skdist",
            chatName = "C",
            ownerId = "me",
            messages = messages.asSequence(),
            resolveSenderName = { it },
            exportedAt = 1600000100000L
        )
        assertNotNull(file)
        val content = file!!.readText()
        assertTrue(content.contains("kept"))
        assertFalse(content.contains("dist-payload"))
        assertTrue(content.contains("format_version=1"))
    }

}
