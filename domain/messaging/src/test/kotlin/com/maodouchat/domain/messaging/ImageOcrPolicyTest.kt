package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageOcrPolicyTest {

    private fun message(content: String = "photo", meta: MessageMeta = MessageMeta()): Message =
        Message(id = "m1", chatId = "c1", senderId = "u1", content = content, meta = meta)

    @Test
    fun `constants match auto indexer limits`() {
        assertEquals("ocr", ImageOcrPolicy.OCR_MODE)
        assertEquals(6_000, ImageOcrPolicy.OCR_RESULT_MAX_CHARS)
        assertEquals(10, ImageOcrPolicy.OCR_IMAGES_PER_RUN)
        assertEquals(200, ImageOcrPolicy.OCR_SCAN_WINDOW)
    }

    @Test
    fun `message without ocr result is not indexed`() {
        assertFalse(ImageOcrPolicy.isAlreadyIndexed(message()))
    }

    @Test
    fun `message with ocr result is indexed`() {
        val indexed = message(meta = MessageMeta(aiImageAnalyses = mapOf("ocr" to "text")))
        assertTrue(ImageOcrPolicy.isAlreadyIndexed(indexed))
    }

    @Test
    fun `withOcrResult writes analysis and prefers ocr mode`() {
        val updated = ImageOcrPolicy.withOcrResult(message(), "hello")
        assertEquals("hello", updated.parsedMeta().aiImageAnalyses["ocr"])
        assertEquals("ocr", updated.parsedMeta().preferredImageAnalysisMode)
        assertEquals("photo", updated.parsedContent())
        assertEquals("hello", updated.meta.aiImageAnalyses["ocr"])
    }

    @Test
    fun `withOcrResult keeps other mode analyses`() {
        val before = message(meta = MessageMeta(aiImageAnalyses = mapOf("describe" to "a cat")))
        val updated = ImageOcrPolicy.withOcrResult(before, "hello")
        assertEquals("a cat", updated.parsedMeta().aiImageAnalyses["describe"])
        assertEquals("hello", updated.parsedMeta().aiImageAnalyses["ocr"])
    }

    @Test
    fun `truncate trims and caps at six thousand chars`() {
        assertEquals("", ImageOcrPolicy.truncateOcrResult("   "))
        assertEquals("abc", ImageOcrPolicy.truncateOcrResult("  abc  "))
        assertEquals(6_000, ImageOcrPolicy.truncateOcrResult("x".repeat(6_001)).length)
    }
}
