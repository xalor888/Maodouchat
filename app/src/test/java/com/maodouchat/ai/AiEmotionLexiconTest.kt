package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEmotionLexiconTest {

    @Test
    fun `tie breaks toward first listed emotion`() {
        // maxByOrNull 取首个最大值：happy 先于 sad
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `hits aggregate across texts with confidence share`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过 哭了"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `scan window truncates beyond 200 chars`() {
        // 关键词被垫到 200 字符窗口之外，不应命中
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(300) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `overlapping dictionary words count per word`() {
        // 「哈哈哈哈」同时含「哈哈」「哈哈哈」，按词计数 2 次
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english matching is case sensitive`() {
        assertEquals(Emotion.NEUTRAL, AiEmotionLexicon.detectEmotion(listOf("LOL GREAT")).emotion)
        assertEquals(Emotion.HAPPY, AiEmotionLexicon.detectEmotion(listOf("lol great")).emotion)
    }
}
