package com.maodouchat.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AiEmotionFallbackTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `happy maps to happy template`() {
        assertEquals(
            context.getString(R.string.ai_emotion_fallback_happy),
            AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        )
    }

    @Test
    fun `sad maps to sad template`() {
        assertEquals(
            context.getString(R.string.ai_emotion_fallback_sad),
            AiEmotionFallback.localFallback(context, Emotion.SAD)
        )
    }

    @Test
    fun `angry maps to angry template`() {
        assertEquals(
            context.getString(R.string.ai_emotion_fallback_angry),
            AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        )
    }

    @Test
    fun `anxious maps to anxious template`() {
        assertEquals(
            context.getString(R.string.ai_emotion_fallback_anxious),
            AiEmotionFallback.localFallback(context, Emotion.ANXIOUS)
        )
    }

    @Test
    fun `neutral maps to neutral template and all five differ`() {
        assertEquals(
            context.getString(R.string.ai_emotion_fallback_neutral),
            AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        )
        val texts = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        assertEquals(5, texts.distinct().size)
        assertTrue(texts.all { it.isNotBlank() })
    }
}
</atem:function_calls>