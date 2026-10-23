package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiClassifyLexiconTest {

    @Test
    fun `tie breaks toward notice`() {
        // maxByOrNull 取首个最大值：entries 顺序里 NOTICE 在前
        val result = AiClassifyLexicon.classifyText("通知 deadline")
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
    fun `scan window truncates beyond 300 chars`() {
        // 关键词被垫到 300 字符窗口之外，不应命中
        val result = AiClassifyLexicon.classifyText("垫".repeat(350) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `no hits falls back to other with low confidence`() {
        val result = AiClassifyLexicon.classifyText("随便聊聊天气")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `classification is deterministic`() {
        val text = "服务器部署完了，接口 bug 修复后发版本"
        val first = AiClassifyLexicon.classifyText(text)
        val second = AiClassifyLexicon.classifyText(text)
        assertEquals(first.category, second.category)
        assertEquals(first.confidence, second.confidence, 0.0001)
    }
}
