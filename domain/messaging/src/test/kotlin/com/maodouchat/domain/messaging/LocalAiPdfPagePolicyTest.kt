package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalAiPdfPagePolicyTest {

    @Test
    fun `page count caps at MAX_PDF_PAGES`() {
        assertEquals(0, LocalAiPdfPagePolicy.cappedPageCount(0))
        assertEquals(4, LocalAiPdfPagePolicy.cappedPageCount(4))
        assertEquals(4, LocalAiPdfPagePolicy.cappedPageCount(5))
        assertEquals(4, LocalAiPdfPagePolicy.cappedPageCount(100))
    }

    @Test
    fun `normal page scales to max width`() {
        assertEquals(1024 to 1280, LocalAiPdfPagePolicy.renderSize(800, 1000))
    }

    @Test
    fun `tiny page clamps to minimum side`() {
        assertEquals(64 to 64, LocalAiPdfPagePolicy.renderSize(10, 10))
    }

    @Test
    fun `wide page keeps aspect within caps`() {
        assertEquals(200 to 100, LocalAiPdfPagePolicy.renderSize(100, 50))
    }

    @Test
    fun `huge page clamps scale floor and height cap`() {
        assertEquals(1024 to 2048, LocalAiPdfPagePolicy.renderSize(8000, 10000))
    }
}
