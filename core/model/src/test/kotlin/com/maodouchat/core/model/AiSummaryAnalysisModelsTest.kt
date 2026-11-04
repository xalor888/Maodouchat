package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSummaryAnalysisModelsTest {

    @Test
    fun `summary scope has six entries in order`() {
        assertEquals(
            listOf(
                AiSummaryScope.RECENT,
                AiSummaryScope.TODAY,
                AiSummaryScope.SEVEN_DAYS,
                AiSummaryScope.THIRTY_DAYS,
                AiSummaryScope.SEARCH_RESULTS,
                AiSummaryScope.UNREAD
            ),
            AiSummaryScope.entries
        )
    }

    @Test
    fun `image analysis modes carry wire values`() {
        assertEquals("describe", AiImageAnalysisMode.DESCRIBE.wireValue)
        assertEquals("ocr", AiImageAnalysisMode.OCR.wireValue)
        assertEquals("safety", AiImageAnalysisMode.SAFETY.wireValue)
        assertEquals(3, AiImageAnalysisMode.entries.size)
    }

    @Test
    fun `file analysis modes carry wire values`() {
        assertEquals("summarize", AiFileAnalysisMode.SUMMARIZE.wireValue)
        assertEquals("question", AiFileAnalysisMode.QUESTION.wireValue)
        assertEquals(2, AiFileAnalysisMode.entries.size)
    }

    @Test
    fun `valueOf round-trips all three enums`() {
        for (entry in AiSummaryScope.entries) {
            assertEquals(entry, AiSummaryScope.valueOf(entry.name))
        }
        for (entry in AiImageAnalysisMode.entries) {
            assertEquals(entry, AiImageAnalysisMode.valueOf(entry.name))
        }
        for (entry in AiFileAnalysisMode.entries) {
            assertEquals(entry, AiFileAnalysisMode.valueOf(entry.name))
        }
    }
}
