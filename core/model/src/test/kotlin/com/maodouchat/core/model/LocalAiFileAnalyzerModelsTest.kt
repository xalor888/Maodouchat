package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiFileAnalyzerModelsTest {

    @Test
    fun `prepared document kinds cover text and pdf pages`() {
        assertEquals(2, PreparedDocumentKind.entries.size)
        assertEquals(PreparedDocumentKind.TEXT, PreparedDocumentKind.valueOf("TEXT"))
        assertEquals(PreparedDocumentKind.PDF_PAGES, PreparedDocumentKind.valueOf("PDF_PAGES"))
    }

    @Test
    fun `prepared document defaults to empty text and no pages`() {
        val doc = PreparedDocument(PreparedDocumentKind.TEXT, "a.txt", "text/plain")
        assertTrue(doc.text.isEmpty())
        assertTrue(doc.pageJpegsBase64.isEmpty())
    }
}
