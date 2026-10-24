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

    @Test
    fun `empty text falls back to other`() {
        val result = AiClassifyLexicon.classifyText("")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `keyword fully inside scan window still counts`() {
        // 298 个垫字 + "转账" 恰好 300 字符，关键词完整落在窗口内
        val result = AiClassifyLexicon.classifyText("垫".repeat(298) + "转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `confidence splits proportionally across categories`() {
        val result = AiClassifyLexicon.classifyText("转账 发票 通知")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword as substring of longer word still matches`() {
        // contains 是子串匹配："debug" 里含 "bug"
        val result = AiClassifyLexicon.classifyText("debug 模式")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `repeated keyword counts once per dictionary word`() {
        // 按词典词计数，不按出现次数："转账转账" 只算 1 次命中
        val result = AiClassifyLexicon.classifyText("转账转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }
}
