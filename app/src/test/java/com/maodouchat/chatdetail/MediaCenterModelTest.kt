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

    @Test
    fun linksAndMediaSortedByTimestampDescendingAcrossCategories() {
        val messages = listOf(
            Message("img", "chat", "u", "content://cached/img", MessageType.IMAGE, 10),
            Message("link", "chat", "u", "see https://a.com/x", MessageType.TEXT, 100),
            Message("voice", "chat", "u", "content://cached/voice", MessageType.VOICE, 50)
        )
        val items = buildMediaCenterItems(messages)
        assertEquals(
            listOf(MediaCenterCategory.LINKS, MediaCenterCategory.VOICE, MediaCenterCategory.MEDIA),
            items.map { it.category }
        )
        assertEquals(listOf("link", "voice", "img"), items.map { it.message.id })
    }

    @Test
    fun multipleLinksInOneMessageKeepTextOrderNotAlphabetical() {
        val message = Message("m", "chat", "u", "first https://z.com/a then https://a.com/z", MessageType.TEXT)
        assertEquals(
            listOf("https://z.com/a", "https://a.com/z"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun stickerMessageContentUrlIsNotCollectedAsLink() {
        val message = Message("m", "chat", "u", "see https://a.com/x", MessageType.STICKER, 10)
        val items = buildMediaCenterItems(listOf(message))
        assertEquals(1, items.size)
        assertEquals(MediaCenterCategory.MEDIA, items.single().category)
        assertEquals(null, items.single().linkUrl)
    }

    @Test
    fun angleBracketTerminatesUrlMatch() {
        val message = Message("m", "chat", "u", "visit https://a.com/path<x now", MessageType.TEXT)
        assertEquals(
            listOf("https://a.com/path"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun minimumLengthHttpUrlIsKept() {
        val message = Message("m", "chat", "u", "看 http://a", MessageType.TEXT)

        assertEquals(listOf("http://a"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }

    @Test
    fun trailingBracketBraceSemicolonAndColonAreTrimmed() {
        val message = Message(
            "m", "chat", "u",
            "见 https://a.com/x; 和 https://b.com/y: 再 (https://c.com/z] 尾 https://d.com/w}",
            MessageType.TEXT
        )

        assertEquals(
            listOf("https://a.com/x", "https://b.com/y", "https://c.com/z", "https://d.com/w"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun queryAndFragmentSurviveTrimming() {
        val message = Message("m", "chat", "u", "看 https://a.com/p?q=1&r=2#frag 好了", MessageType.TEXT)

        assertEquals(listOf("https://a.com/p?q=1&r=2#frag"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }

    @Test
    fun fileAndVideoContentUrlsNeverBecomeLinkItems() {
        val messages = listOf(
            Message("f", "chat", "u", "https://a.com/doc.pdf", MessageType.FILE, 20),
            Message("v", "chat", "u", "https://a.com/clip.mp4", MessageType.VIDEO, 10)
        )

        val items = buildMediaCenterItems(messages)

        assertEquals(2, items.size)
        assertEquals(listOf(MediaCenterCategory.FILES, MediaCenterCategory.MEDIA), items.map { it.category })
        assertEquals(listOf(null, null), items.map { it.linkUrl })
    }

    @Test
    fun newlineTerminatesUrlMatch() {
        val message = Message("m", "chat", "u", "看 https://a.com/path\n下一行 https://b.com/other", MessageType.TEXT)

        assertEquals(
            listOf("https://a.com/path", "https://b.com/other"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun sameTimestampItemsKeepInputOrder() {
        val messages = listOf(
            Message("file", "chat", "u", "content://cached/file", MessageType.FILE, 10),
            Message("voice", "chat", "u", "content://cached/voice", MessageType.VOICE, 10),
            Message("img", "chat", "u", "content://cached/img", MessageType.IMAGE, 10)
        )
        val items = buildMediaCenterItems(messages)
        assertEquals(listOf("file", "voice", "img"), items.map { it.message.id })
        assertEquals(
            listOf(MediaCenterCategory.FILES, MediaCenterCategory.VOICE, MediaCenterCategory.MEDIA),
            items.map { it.category }
        )
    }

    @Test
    fun urlTrimmedBelowMinimumLengthIsDropped() {
        val message = Message("m", "chat", "u", "看 http://. 好", MessageType.TEXT)
        assertEquals(emptyList(), buildMediaCenterItems(listOf(message)))
    }

    @Test
    fun exclamationAndQuestionMarksAreTrimmedFromUrls() {
        val message = Message("m", "chat", "u", "见 https://a.com/x! 和 https://b.com/y? 尾", MessageType.TEXT)
        assertEquals(
            listOf("https://a.com/x", "https://b.com/y"),
            buildMediaCenterItems(listOf(message)).map { it.linkUrl }
        )
    }

    @Test
    fun locationContentUrlIsNotExtractedAsLink() {
        val message = Message("loc", "chat", "u", "https://a.com/x", MessageType.LOCATION, 10)
        val items = buildMediaCenterItems(listOf(message))
        assertEquals(1, items.size)
        assertEquals(MediaCenterCategory.LOCATION, items.single().category)
        assertEquals(null, items.single().linkUrl)
    }

    @Test
    fun revokedSystemAndNudgeMessagesYieldNoItems() {
        val messages = listOf(
            Message("r", "chat", "u", "见 https://a.com/x", MessageType.REVOKED, 30),
            Message("s", "chat", "u", "见 https://a.com/x", MessageType.SYSTEM, 20),
            Message("n", "chat", "u", "见 https://a.com/x", MessageType.NUDGE, 10)
        )
        assertEquals(emptyList(), buildMediaCenterItems(messages))
    }

    @Test
    fun duplicateLinksAfterPunctuationTrimmingCollapseToOne() {
        val message = Message("m", "chat", "u", "见 https://a.com/x, 再 https://a.com/x", MessageType.TEXT)
        assertEquals(listOf("https://a.com/x"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }


    @Test
    fun urlWithPortAndQuerySurvivesIntact() {
        val message = Message("m", "chat", "u", "看 https://a.com:8080/p?q=1&r=2 好了", MessageType.TEXT)

        assertEquals(listOf("https://a.com:8080/p?q=1&r=2"), buildMediaCenterItems(listOf(message)).map { it.linkUrl })
    }

    @Test
    fun schemesWithoutHttpAndBareWwwYieldNoLinks() {
        val message = Message("m", "chat", "u", "见 www.a.com 和 ftp://f.com/x", MessageType.TEXT)

        assertEquals(emptyList(), buildMediaCenterItems(listOf(message)))
    }

    @Test
    fun hiddenSkDistMessagesYieldNoItems() {
        val message = Message("m", "chat", "u", "见 https://a.com/x", MessageType.SK_DIST)

        assertEquals(emptyList(), buildMediaCenterItems(listOf(message)))
    }

    @Test
    fun voiceMessageContentUrlNeverBecomesALinkItem() {
        val message = Message("m", "chat", "u", "https://a.com/x", MessageType.VOICE, 10)
        val items = buildMediaCenterItems(listOf(message))

        assertEquals(1, items.size)
        assertEquals(MediaCenterCategory.VOICE, items.single().category)
        assertEquals(null, items.single().linkUrl)
    }

    @Test
    fun sameUrlInThreeMessagesYieldsOneItemPerMessageNewestFirst() {
        val messages = listOf(
            Message("m1", "chat", "u", "见 https://a.com/x", MessageType.TEXT, 10),
            Message("m2", "chat", "u", "见 https://a.com/x", MessageType.TEXT, 30),
            Message("m3", "chat", "u", "见 https://a.com/x", MessageType.TEXT, 20)
        )
        val items = buildMediaCenterItems(messages)

        assertEquals(3, items.size)
        assertEquals(listOf("m2", "m3", "m1"), items.map { it.message.id })
        assertEquals(listOf("https://a.com/x", "https://a.com/x", "https://a.com/x"), items.map { it.linkUrl })
    }

}
