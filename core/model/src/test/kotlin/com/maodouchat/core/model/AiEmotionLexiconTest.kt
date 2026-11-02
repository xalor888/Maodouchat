package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEmotionLexiconTest {

    @Test
    fun `single happy hit returns full confidence`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("太好了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty input is neutral`() {
        val result = AiEmotionLexicon.detectEmotion(emptyList())
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword beyond 200 char window is ignored`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("a".repeat(250) + "love"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword at window edge still counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("a".repeat(196) + "love"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry wins on multiple hits`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("气死我了", "烦死了"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }
}
