package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MediaCenterCategory
import com.maodouchat.ui.screen.chatdetail.buildMediaCenterItems
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCenterModelTest {
    @Test
    fun classifiesMediaFilesVoiceLocationAndLinksWithoutCopyingMessageContent() {
        val messages = listOf(
            Message("image", "chat", "u1", "content://cached/image", MessageType.IMAGE, 60),
            Message("sticker", "chat", "u1", "content://cached/sticker", MessageType.STICKER, 50),
            Message("file", "chat", "u1", "content://cached/file", MessageType.FILE, 40),
            Message("voice", "chat", "u1", "content://cached/voice", MessageType.VOICE, 30),
            Message("loc", "chat", "u1", """{"latitude":31.2,"longitude":121.5,"label":"外滩"}""", MessageType.LOCATION, 20),
            Message("text", "chat", "u2", "查看 https://example.com/a 和 https://openai.com。", MessageType.TEXT, 10)
        )

        val items = buildMediaCenterItems(messages)

        assertEquals(
            listOf(
                MediaCenterCategory.MEDIA,
                MediaCenterCategory.MEDIA,
                MediaCenterCategory.FILES,
                MediaCenterCategory.VOICE,
                MediaCenterCategory.LOCATION,
                MediaCenterCategory.LINKS,
                MediaCenterCategory.LINKS
            ),
            items.map { it.category }
        )
        assertEquals(listOf("https://example.com/a", "https://openai.com"), items.filter { it.category == MediaCenterCategory.LINKS }.map { it.linkUrl })
        assertEquals("content://cached/image", items.first().message.content)
    }

    @Test
    fun ignoresUnsupportedSchemesAndDeduplicatesLinksPerMessage() {
        val message = Message("m", "chat", "u", "javascript:bad https://safe.example https://safe.example", MessageType.TEXT)
        assertEquals(listOf("https://safe.example"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }
}
