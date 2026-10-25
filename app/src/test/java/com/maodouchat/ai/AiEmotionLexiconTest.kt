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

    @Test
    fun `empty input yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(emptyList())
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `blank text yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("   "))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword fully inside scan window still counts`() {
        // 198 个垫字 + "哈哈" 恰好 200 字符，关键词完整落在窗口内
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(198) + "哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious words are detected`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("我很焦虑，压力好大"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `cross valence tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "气死我了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `angry words aggregate across overlapping entries`() {
        // 「烦死了」同时命中「烦死了」与「烦」，按词计数 3 次
        val result = AiEmotionLexicon.detectEmotion(listOf("气死我了，烦死了"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english happy words aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("so happy love it"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad aggregates across separate texts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "哭了"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword split by scan window edge does not count`() {
        // 199 个垫字 + "哈哈"：窗口只含 "哈" 一个字，凑不成关键词
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(199) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `scan window applies per text not across the list`() {
        // 首条文本的关键词在窗口外不命中，但不影响第二条文本的命中
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(300) + "哈哈", "难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `three way tie breaks toward first listed emotion`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过", "生气"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad versus anxious tie breaks toward sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "焦虑"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `repeated occurrence of one word counts once`() {
        // 按词典词条 presence 计数：同一词出现多次仍只计 1 次
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 哈哈 哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty string element yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf(""))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious english words aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("so worried nervous"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

}
