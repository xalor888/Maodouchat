package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiEmotionFallbackKindTest {

    @Test
    fun `all five emotions map one to one`() {
        assertEquals(AiEmotionFallbackKind.HAPPY, aiEmotionFallbackKind(Emotion.HAPPY))
        assertEquals(AiEmotionFallbackKind.SAD, aiEmotionFallbackKind(Emotion.SAD))
        assertEquals(AiEmotionFallbackKind.ANGRY, aiEmotionFallbackKind(Emotion.ANGRY))
        assertEquals(AiEmotionFallbackKind.ANXIOUS, aiEmotionFallbackKind(Emotion.ANXIOUS))
        assertEquals(AiEmotionFallbackKind.NEUTRAL, aiEmotionFallbackKind(Emotion.NEUTRAL))
    }
}
