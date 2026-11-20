package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PreparedAttachmentTest {

    @Test
    fun `prepared attachment keeps fields`() {
        val prepared = PreparedAttachment(
            chatId = "c1",
            sourceUri = "content://media/1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            plainSize = 1234L
        )
        assertEquals("c1", prepared.chatId)
        assertEquals("content://media/1", prepared.sourceUri)
        assertEquals("photo.jpg", prepared.fileName)
        assertEquals("image/jpeg", prepared.mimeType)
        assertEquals(1234L, prepared.plainSize)
    }

    @Test
    fun `prepared attachment equality is structural`() {
        val a = PreparedAttachment("c1", "content://media/1", "a.jpg", "image/jpeg", 10L)
        val b = PreparedAttachment("c1", "content://media/1", "a.jpg", "image/jpeg", 10L)
        assertEquals(a, b)
        assertNotEquals(a, a.copy(plainSize = 11L))
    }

    @Test
    fun `prepared attachment copy changes one field`() {
        val a = PreparedAttachment("c1", "content://media/1", "a.jpg", "image/jpeg", 10L)
        val copy = a.copy(fileName = "b.jpg")
        assertEquals("b.jpg", copy.fileName)
        assertEquals(10L, copy.plainSize)
    }
}
