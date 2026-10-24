package com.maodouchat.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `fallback is deterministic across repeated calls`() {
        assertEquals(
            AiEmotionFallback.localFallback(context, Emotion.ANGRY),
            AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        )
    }

    @Test
    fun `no template contains a format placeholder`() {
        // 模板都是静态文本：含 % 占位符的 getString 在无参数时会抛错
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含 % 占位符", text.contains("%"))
        }
    }

    @Test
    fun `every emotion entry resolves to its own template`() {
        Emotion.entries.forEach { emotion ->
            val expected = when (emotion) {
                Emotion.HAPPY -> R.string.ai_emotion_fallback_happy
                Emotion.SAD -> R.string.ai_emotion_fallback_sad
                Emotion.ANGRY -> R.string.ai_emotion_fallback_angry
                Emotion.ANXIOUS -> R.string.ai_emotion_fallback_anxious
                Emotion.NEUTRAL -> R.string.ai_emotion_fallback_neutral
            }
            assertEquals(context.getString(expected), AiEmotionFallback.localFallback(context, emotion))
        }
    }

    @Test
    fun `interleaved calls do not leak state`() {
        val happyFirst = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        val sad = AiEmotionFallback.localFallback(context, Emotion.SAD)
        val happyAgain = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        assertEquals(happyFirst, happyAgain)
        assertTrue(happyFirst != sad)
    }

    @Test
    fun `repeated stress calls never return blank`() {
        repeat(20) {
            Emotion.entries.forEach { emotion ->
                assertTrue(AiEmotionFallback.localFallback(context, emotion).isNotBlank())
            }
        }
    }
}
