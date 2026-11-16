package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Emotion
import kotlin.test.Test
import kotlin.test.assertEquals

class AiEmotionTonePolicyTest {

    @Test
    fun `all five emotions map to their tones`() {
        assertEquals("warm", AiEmotionTonePolicy.toneFor(Emotion.HAPPY))
        assertEquals("empathetic", AiEmotionTonePolicy.toneFor(Emotion.SAD))
        assertEquals("gentle", AiEmotionTonePolicy.toneFor(Emotion.ANGRY))
        assertEquals("encouraging", AiEmotionTonePolicy.toneFor(Emotion.ANXIOUS))
        assertEquals("friendly", AiEmotionTonePolicy.toneFor(Emotion.NEUTRAL))
    }
}
