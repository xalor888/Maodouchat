package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalSearchTypeFilterTest {

    @Test
    fun allReturnsNull() {
        assertNull(GlobalSearchTypeFilter.messageTypesForFilter("ALL"))
    }

    @Test
    fun textMapsToTextAndMarkdown() {
        assertEquals(
            listOf("TEXT", "MARKDOWN"),
            GlobalSearchTypeFilter.messageTypesForFilter("TEXT"),
        )
    }

    @Test
    fun imageMapsToImageGifSticker() {
        assertEquals(
            listOf("IMAGE", "GIF", "STICKER"),
            GlobalSearchTypeFilter.messageTypesForFilter("IMAGE"),
        )
    }

    @Test
    fun fileVoiceVideoMapToSingleType() {
        assertEquals(listOf("FILE"), GlobalSearchTypeFilter.messageTypesForFilter("FILE"))
        assertEquals(listOf("VOICE"), GlobalSearchTypeFilter.messageTypesForFilter("VOICE"))
        assertEquals(listOf("VIDEO"), GlobalSearchTypeFilter.messageTypesForFilter("VIDEO"))
    }

    @Test
    fun linkMapsToTextAndMarkdown() {
        assertEquals(
            listOf("TEXT", "MARKDOWN"),
            GlobalSearchTypeFilter.messageTypesForFilter("LINK"),
        )
    }

    @Test
    fun unknownFilterReturnsNull() {
        assertNull(GlobalSearchTypeFilter.messageTypesForFilter("UNKNOWN"))
        assertNull(GlobalSearchTypeFilter.messageTypesForFilter(""))
    }

    @Test
    fun schemeUrlIsLink() {
        assertTrue(GlobalSearchTypeFilter.isLinkText("https://example.com/x"))
        assertTrue(GlobalSearchTypeFilter.isLinkText("http://a.b"))
        assertTrue(GlobalSearchTypeFilter.isLinkText("ftp://files/x"))
    }

    @Test
    fun bareKnownDomainsAreLinks() {
        assertTrue(GlobalSearchTypeFilter.isLinkText("www.example.com"))
        assertTrue(GlobalSearchTypeFilter.isLinkText("WWW.EXAMPLE.COM"))
        assertTrue(GlobalSearchTypeFilter.isLinkText("see t.me/maodou"))
        assertTrue(GlobalSearchTypeFilter.isLinkText("chat.mdou.me/abc"))
    }

    @Test
    fun plainTextIsNotLink() {
        assertFalse(GlobalSearchTypeFilter.isLinkText("plain text without url"))
        assertFalse(GlobalSearchTypeFilter.isLinkText("example.com/path"))
        assertFalse(GlobalSearchTypeFilter.isLinkText("xt.me/no-boundary"))
    }
}
