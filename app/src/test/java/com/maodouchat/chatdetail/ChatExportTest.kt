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
}
