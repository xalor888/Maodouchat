package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MediaCenterCategory
import com.maodouchat.ui.screen.chatdetail.buildMediaCenterItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

    @Test
    fun classifiesGifAndVideoAsMediaToo() {
        val messages = listOf(
            Message("g", "chat", "u", "content://cached/a.gif", MessageType.GIF, 30),
            Message("v", "chat", "u", "content://cached/b.mp4", MessageType.VIDEO, 40)
        )
        assertEquals(
            listOf(MediaCenterCategory.MEDIA, MediaCenterCategory.MEDIA),
            buildMediaCenterItems(messages).map { it.category }
        )
    }

    @Test
    fun sortsItemsByTimestampDescendingRegardlessOfInputOrder() {
        val messages = listOf(
            Message("img", "chat", "u", "content://cached/img", MessageType.IMAGE, 10),
            Message("file", "chat", "u", "content://cached/file", MessageType.FILE, 50),
            Message("voice", "chat", "u", "content://cached/voice", MessageType.VOICE, 30)
        )
        val items = buildMediaCenterItems(messages)
        assertEquals(listOf(50L, 30L, 10L), items.map { it.message.timestamp })
        assertEquals(
            listOf(MediaCenterCategory.FILES, MediaCenterCategory.VOICE, MediaCenterCategory.MEDIA),
            items.map { it.category }
        )
    }

    @Test
    fun trimsTrailingUrlPunctuationIncludingFullWidth() {
        val message = Message(
            "m", "chat", "u",
            "见 https://example.com， 尾 (https://a.com/foo)",
            MessageType.TEXT
        )
        assertEquals(
            listOf("https://example.com", "https://a.com/foo"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun keepsMaxLengthUrlButRejectsOverlongUrl() {
        val okUrl = "https://" + "a".repeat(2040) // 2048 chars
        val tooLong = "https://" + "a".repeat(2041) // 2049 chars
        val items = buildMediaCenterItems(
            listOf(
                Message("ok", "chat", "u", "x $okUrl", MessageType.TEXT),
                Message("long", "chat", "u", "x $tooLong", MessageType.TEXT)
            )
        )
        assertEquals(listOf(okUrl), items.map { it.linkUrl })
    }

    @Test
    fun textMessageWithoutUrlsYieldsNoItems() {
        val message = Message("m", "chat", "u", "纯文本，无链接", MessageType.TEXT)
        assertEquals(emptyList(), buildMediaCenterItems(listOf(message)))
        assertEquals(emptyList(), buildMediaCenterItems(emptyList()))
    }

    @Test
    fun httpLinksAreCollectedAlongsideHttps() {
        val message = Message("m", "chat", "u", "打开 http://example.com/a 看看", MessageType.TEXT)

        val items = buildMediaCenterItems(listOf(message))

        assertEquals(listOf("http://example.com/a"), items.map { it.linkUrl })
        assertEquals(listOf(MediaCenterCategory.LINKS), items.map { it.category })
    }

    @Test
    fun markdownTypeNeverYieldsLinkItems() {
        val message = Message("m", "chat", "u", "# 标题 https://example.com/x", MessageType.MARKDOWN)

        assertEquals(emptyList(), buildMediaCenterItems(listOf(message)))
    }

    @Test
    fun sameUrlInTwoMessagesYieldsOneItemPerMessage() {
        val messages = listOf(
            Message("m1", "chat", "u", "见 https://a.com/foo", MessageType.TEXT, 10),
            Message("m2", "chat", "u", "再发一次 https://a.com/foo", MessageType.TEXT, 20)
        )

        val items = buildMediaCenterItems(messages)

        assertEquals(2, items.size)
        assertEquals(listOf("m2", "m1"), items.map { it.message.id })
    }

    @Test
    fun uppercaseSchemeMatchesCaseInsensitively() {
        val message = Message("m", "chat", "u", "访问 HTTPS://Example.com/Path", MessageType.TEXT)

        assertEquals(listOf("HTTPS://Example.com/Path"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }

    @Test
    fun itemHoldsTheOriginalMessageReference() {
        val message = Message("m", "chat", "u", "content://cached/img", MessageType.IMAGE, 10)

        val item = buildMediaCenterItems(listOf(message)).single()

        assertSame(message, item.message)
    }
}
