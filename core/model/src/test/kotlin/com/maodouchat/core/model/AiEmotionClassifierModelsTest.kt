package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEmotionClassifierModelsTest {

    @Test
    fun `emotion wires keep their order`() {
        assertEquals(
            listOf("happy", "sad", "angry", "anxious", "neutral"),
            Emotion.entries.map { it.wire }
        )
    }

    @Test
    fun `emotion result keeps its fields and copies`() {
        val result = EmotionResult(Emotion.HAPPY, 0.8)
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.8, result.confidence, 0.0)
        assertEquals(result, result.copy(confidence = 0.8))
    }

    @Test
    fun `classifier categories cover their wires`() {
        assertEquals(
            listOf("notice", "todo", "finance", "study", "tech", "social", "other"),
            AiMessageClassifierCategory.entries.map { it.wire }
        )
    }

    @Test
    fun `classification keeps its fields and copies`() {
        val result = Classification(AiMessageClassifierCategory.TODO, 0.5)
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(0.5, result.confidence, 0.0)
        assertEquals(result, result.copy(confidence = 0.5))
    }
}
