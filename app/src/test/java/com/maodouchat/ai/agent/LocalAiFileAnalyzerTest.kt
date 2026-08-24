package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LocalAiFileAnalyzerTest {

    @Test
    fun `text files decode without a network call`() {
        val body = "会议纪要：周六发布"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("meeting.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(LocalAiFileAnalyzer.Kind.TEXT, prepared!!.kind)
        assertTrue(prepared.text.contains("周六发布"))
    }

    @Test
    fun `binary junk is rejected`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, 0))
        assertNull(LocalAiFileAnalyzer.prepare("blob.bin", "application/octet-stream", encoded))
    }

    @Test
    fun `invalid pdf bytes are rejected`() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-not-a-real-file".toByteArray())
        assertNull(LocalAiFileAnalyzer.prepare("fake.pdf", "application/pdf", encoded))
    }
}
