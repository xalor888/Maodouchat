package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MediaCenterCategory
import com.maodouchat.core.model.MediaCenterItem
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCenterSearchTest {

    private fun msg(content: String, type: MessageType = MessageType.TEXT, meta: MessageMeta = MessageMeta()) =
        Message(id = "m1", chatId = "c1", senderId = "u1", content = content, type = type, meta = meta)

    private fun item(content: String, category: MediaCenterCategory, type: MessageType = MessageType.TEXT, meta: MessageMeta = MessageMeta(), link: String? = null) =
        MediaCenterItem(msg(content, type, meta), category, link)

    @Test
    fun fileNameMatches() {
        val it = item("", MediaCenterCategory.FILES, MessageType.FILE, MessageMeta(fileName = "年度报告.pdf"))
        assertTrue(mediaCenterItemMatches(it, "报告"))
        assertTrue(mediaCenterItemMatches(it, "PDF"))
        assertFalse(mediaCenterItemMatches(it, "发票"))
    }

    @Test
    fun contentMatchesCaseInsensitively() {
        val it = item("Hello World", MediaCenterCategory.MEDIA, MessageType.IMAGE)
        assertTrue(mediaCenterItemMatches(it, "world"))
        assertTrue(mediaCenterItemMatches(it, "HELLO"))
        assertFalse(mediaCenterItemMatches(it, "bye"))
    }

    @Test
    fun mimeMatches() {
        val it = item("", MediaCenterCategory.FILES, MessageType.FILE, MessageMeta(fileName = "a.bin", fileMimeType = "application/pdf"))
        assertTrue(mediaCenterItemMatches(it, "pdf"))
        assertFalse(mediaCenterItemMatches(it, "image"))
    }

    @Test
    fun linkMatches() {
        val it = item("看看这个", MediaCenterCategory.LINKS, link = "https://example.com/doc")
        assertTrue(mediaCenterItemMatches(it, "example"))
        assertFalse(mediaCenterItemMatches(it, "other.org"))
    }

    @Test
    fun locationLabelMatches() {
        val it = item(
            "{\"latitude\":31.2,\"longitude\":121.5,\"label\":\"外滩\"}",
            MediaCenterCategory.LOCATION, MessageType.LOCATION,
        )
        assertTrue(mediaCenterItemMatches(it, "外滩"))
        assertFalse(mediaCenterItemMatches(it, "陆家嘴"))
    }

    @Test
    fun blankFileNameFallsBackToContent() {
        val it = item("会议纪要正文", MediaCenterCategory.FILES, MessageType.FILE)
        assertTrue(mediaCenterItemMatches(it, "会议纪要"))
        assertFalse(mediaCenterItemMatches(it, "报告"))
    }
}
