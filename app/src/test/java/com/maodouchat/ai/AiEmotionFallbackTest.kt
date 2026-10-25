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

    @Test
    fun `two full sweeps return identical lists`() {
        val first = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        val second = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        assertEquals(first, second)
    }

    @Test
    fun `reverse order pass matches reversed forward pass`() {
        val forward = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        val reversed = Emotion.entries.reversed().map { AiEmotionFallback.localFallback(context, it) }
        assertEquals(forward.reversed(), reversed)
    }

    @Test
    fun `no template leaks its resource name`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含资源名", text.contains("ai_emotion_fallback"))
        }
    }

    @Test
    fun `no template has surrounding whitespace`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertEquals("$emotion 的模板不应有首尾空白", text.trim(), text)
        }
    }

    @Test
    fun `no template contains a newline`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板应为单行", text.contains("\n"))
        }
    }

    @Test
    fun `every template ends with sentence-final punctuation`() {
        // 中英文两套文案都满足这些形态约束（已核对 values 与 values-en）。
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertTrue("$emotion 的模板应以句末标点结尾", text.last() in setOf('。', '！', '.', '!'))
        }
    }

    @Test
    fun `no template contains an ASCII digit`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含数字", text.any { it.isDigit() })
        }
    }

    @Test
    fun `every template contains a comma pause`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertTrue("$emotion 的模板应含逗号停顿", text.contains('，') || text.contains(','))
        }
    }

    @Test
    fun `happy template is the shortest of the five`() {
        val happy = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        Emotion.entries.filter { it != Emotion.HAPPY }.forEach { emotion ->
            assertTrue("$emotion 的模板应长于 happy 模板",
                AiEmotionFallback.localFallback(context, emotion).length > happy.length)
        }
    }

    @Test
    fun `no template contains a double space`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含双空格", text.contains("  "))
        }
    }

    @Test
    fun `every template is shorter than eighty characters`() {
        val texts = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        assertTrue(texts.all { it.length < 80 })
    }

    @Test
    fun `no sentence-final mark appears before the last character`() {
        val marks = setOf('。', '！', '？', '.', '!', '?')
        val texts = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        assertTrue(texts.all { text -> text.dropLast(1).none { it in marks } })
    }

    @Test
    fun `happy template is shorter than sad and anxious templates`() {
        val happy = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        val sad = AiEmotionFallback.localFallback(context, Emotion.SAD)
        val anxious = AiEmotionFallback.localFallback(context, Emotion.ANXIOUS)
        assertTrue(happy.length < sad.length)
        assertTrue(happy.length < anxious.length)
    }

    @Test
    fun `no template contains a tab or carriage return`() {
        val texts = Emotion.entries.map { AiEmotionFallback.localFallback(context, it) }
        assertTrue(texts.none { it.contains('\t') || it.contains('\r') })
    }

    @Test
    fun `only the happy template ends with an exclamation mark`() {
        val texts = Emotion.entries.map { it to AiEmotionFallback.localFallback(context, it) }
        for ((emotion, text) in texts) {
            val endsWithBang = text.endsWith('!') || text.endsWith('！')
            assertEquals(emotion == Emotion.HAPPY, endsWithBang)
        }
    }

    @Test
    fun `only sad and angry templates express understanding`() {
        // 中英文文案里表达理解的只有 sad 与 angry 两条
        val expressing = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("理解") || text.contains("understand", ignoreCase = true)
        }
        assertEquals(setOf(Emotion.SAD, Emotion.ANGRY), expressing.toSet())
    }

    @Test
    fun `anxious template is the longest of the five`() {
        val anxious = AiEmotionFallback.localFallback(context, Emotion.ANXIOUS)
        Emotion.entries.filter { it != Emotion.ANXIOUS }.forEach { emotion ->
            assertTrue("$emotion 的模板应短于 anxious 模板",
                AiEmotionFallback.localFallback(context, emotion).length < anxious.length)
        }
    }

    @Test
    fun `only the happy template names happiness`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("开心") || text.contains("happy", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.HAPPY), naming)
    }

    @Test
    fun `no template asks a question`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含问号", text.contains('？') || text.contains('?'))
        }
    }

    @Test
    fun `neutral template is shorter than sad angry and anxious templates`() {
        val neutral = AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        listOf(Emotion.SAD, Emotion.ANGRY, Emotion.ANXIOUS).forEach { emotion ->
            assertTrue("$emotion 的模板应长于 neutral 模板",
                AiEmotionFallback.localFallback(context, emotion).length > neutral.length)
        }
    }

    @Test
    fun `only happy and sad templates have exactly one comma`() {
        val counts = Emotion.entries.associateWith { emotion ->
            AiEmotionFallback.localFallback(context, emotion).count { it == '，' || it == ',' }
        }
        assertEquals(
            mapOf(
                Emotion.HAPPY to 1,
                Emotion.SAD to 1,
                Emotion.ANGRY to 2,
                Emotion.ANXIOUS to 2,
                Emotion.NEUTRAL to 2
            ),
            counts
        )
    }

    @Test
    fun `only neutral template mentions listening`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("听") || text.contains("listening", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.NEUTRAL), naming)
    }

    @Test
    fun `only anxious template mentions step by step`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("一步一步") || text.contains("step by step", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.ANXIOUS), naming)
    }

    @Test
    fun `only sad template offers anytime talk`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("随时") || text.contains("whenever", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.SAD), naming)
    }

    @Test
    fun `only angry template asks to go slowly`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("慢慢") || text.contains("slowly", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.ANGRY), naming)
    }

    @Test
    fun `sad template is longer than happy template`() {
        val happy = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        val sad = AiEmotionFallback.localFallback(context, Emotion.SAD)
        assertTrue(sad.length > happy.length)
    }

    @Test
    fun `angry template is longer than neutral template`() {
        val angry = AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        val neutral = AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        assertTrue(angry.length > neutral.length)
    }

    @Test
    fun `only anxious template mentions companionship`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("陪") || text.contains("together", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.ANXIOUS), naming)
    }

    @Test
    fun `only angry template says take it easy`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("别急") || text.contains("Take it easy")
        }
        assertEquals(listOf(Emotion.ANGRY), naming)
    }

    @Test
    fun `only neutral template invites speaking`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("你说") || text.contains("go ahead", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.NEUTRAL), naming)
    }
}
