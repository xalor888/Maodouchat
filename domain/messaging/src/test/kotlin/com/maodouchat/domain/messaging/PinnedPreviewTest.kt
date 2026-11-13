package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedPreviewTest {
    private fun message(content: String, type: MessageType = MessageType.TEXT) =
        Message(id = "m1", chatId = "c1", senderId = "u1", content = content, type = type)

    @Test
    fun `null message falls back to generic`() {
        assertEquals(PinnedPreview.Generic, pinnedPreview(null))
    }

    @Test
    fun `text message keeps its content`() {
        assertEquals(PinnedPreview.Text("hello"), pinnedPreview(message("hello")))
    }

    @Test
    fun `blank text stays blank for the app shell to map to generic`() {
        assertEquals(PinnedPreview.Text(""), pinnedPreview(message("   ")))
    }

    @Test
    fun `markdown is previewed as text`() {
        assertEquals(PinnedPreview.Text("**hi**"), pinnedPreview(message("**hi**", MessageType.MARKDOWN)))
    }

    @Test
    fun `image and gif share the image preview`() {
        assertEquals(PinnedPreview.Image, pinnedPreview(message("", MessageType.IMAGE)))
        assertEquals(PinnedPreview.Image, pinnedPreview(message("", MessageType.GIF)))
    }

    @Test
    fun `media and file types map to their own previews`() {
        assertEquals(PinnedPreview.Voice, pinnedPreview(message("", MessageType.VOICE)))
        assertEquals(PinnedPreview.Video, pinnedPreview(message("", MessageType.VIDEO)))
        assertEquals(PinnedPreview.File, pinnedPreview(message("", MessageType.FILE)))
        assertEquals(PinnedPreview.Location, pinnedPreview(message("", MessageType.LOCATION)))
        assertEquals(PinnedPreview.Sticker, pinnedPreview(message("", MessageType.STICKER)))
    }

    @Test
    fun `unknown type falls back to generic`() {
        assertEquals(PinnedPreview.Generic, pinnedPreview(message("", MessageType.NUDGE)))
    }

    @Test
    fun `long text is truncated to 96 chars`() {
        val preview = pinnedPreview(message("x".repeat(200)))
        assertTrue(preview is PinnedPreview.Text)
        assertEquals("x".repeat(95) + "…", (preview as PinnedPreview.Text).text)
    }

    @Test
    fun `trailing meta block is stripped from text`() {
        assertEquals(
            PinnedPreview.Text("hello"),
            pinnedPreview(message("hello<meta>{}</meta>"))
        )
    }
}
