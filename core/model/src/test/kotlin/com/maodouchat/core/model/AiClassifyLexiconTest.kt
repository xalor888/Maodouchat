package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiClassifyLexiconTest {

    @Test
    fun `tie breaks toward notice`() {
        val result = AiClassifyLexicon.classifyText("\u901a\u77e5 deadline")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english matching ignores case`() {
        val result = AiClassifyLexicon.classifyText("INVOICE")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `no hits falls back to other with low confidence`() {
        val result = AiClassifyLexicon.classifyText("\u968f\u4fbf\u804a\u804a\u5929\u6c14")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `scan window truncates beyond 300 chars`() {
        val result = AiClassifyLexicon.classifyText("\u57ab".repeat(350) + "\u8f6c\u8d26")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `majority hits win over single hit`() {
        val result = AiClassifyLexicon.classifyText("\u8f6c\u8d26 \u53d1\u7968 \u901a\u77e5")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }
}
