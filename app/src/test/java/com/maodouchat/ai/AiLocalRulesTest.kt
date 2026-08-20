package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 本地规则组件纯函数测试（9.231）：分类器与情绪检测的词典规则锁定。
 */
class AiLocalRulesTest {

    @Test
    fun `classify text picks dominant lexicon`() {
        assertEquals(
            AiMessageClassifier.Category.FINANCE,
            AiMessageClassifier.classifyText("记得转账，账单发我，付款截图也发一下").category
        )
        assertEquals(
            AiMessageClassifier.Category.TECH,
            AiMessageClassifier.classifyText("服务器部署完了，接口 bug 修复后发版本").category
        )
    }

    @Test
    fun `classify text without hits falls back to other`() {
        val result = AiMessageClassifier.classifyText("随便聊聊天气")
        assertEquals(AiMessageClassifier.Category.OTHER, result.category)
        assertTrue(result.confidence < 0.5)
    }

    @Test
    fun `classify confidence is share of hits`() {
        // 单词典全命中时置信度为 1.0
        val result = AiMessageClassifier.classifyText("deadline 截止 提交 待办")
        assertEquals(AiMessageClassifier.Category.TODO, result.category)
        assertTrue(result.confidence > 0.6)
    }

    @Test
    fun `classify truncates long input to scan window`() {
        // 关键词在 300 字符窗口外不应命中
        val text = "垫".repeat(400) + "转账 账单"
        val result = AiMessageClassifier.classifyText(text)
        assertEquals(AiMessageClassifier.Category.OTHER, result.category)
    }

    @Test
    fun `detect emotion picks strongest signal`() {
        val result = AiEmotionReply.detectEmotion(listOf("今天好开心哈哈", "太棒了"))
        assertEquals(AiEmotionReply.Emotion.HAPPY, result.emotion)
        assertTrue(result.confidence > 0.5)
    }

    @Test
    fun `detect emotion neutral when no hits`() {
        val result = AiEmotionReply.detectEmotion(listOf("会议改到三点"))
        assertEquals(AiEmotionReply.Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion empty input is neutral`() {
        assertEquals(AiEmotionReply.Emotion.NEUTRAL, AiEmotionReply.detectEmotion(emptyList()).emotion)
    }

    @Test
    fun `detect emotion mixed signals take majority`() {
        val result = AiEmotionReply.detectEmotion(listOf("难过 伤心", "难过"))
        assertEquals(AiEmotionReply.Emotion.SAD, result.emotion)
    }
}
