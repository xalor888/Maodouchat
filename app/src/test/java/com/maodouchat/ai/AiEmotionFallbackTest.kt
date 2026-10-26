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


    @Test
    fun `anxious template is longer than angry template`() {
        val anxious = AiEmotionFallback.localFallback(context, Emotion.ANXIOUS)
        val angry = AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        assertTrue(anxious.length > angry.length)
    }

    @Test
    fun `happy and neutral templates are the two shortest`() {
        val shortest = Emotion.entries
            .map { it to AiEmotionFallback.localFallback(context, it) }
            .sortedBy { it.second.length }
            .take(2)
            .map { it.first }
            .toSet()
        assertEquals(setOf(Emotion.HAPPY, Emotion.NEUTRAL), shortest)
    }

    @Test
    fun `every template has distinct comma-separated clauses`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            val clauses = text.split('，', ',').map { it.trim() }
            assertEquals("$emotion 的模板分句不应重复", clauses.size, clauses.distinct().size)
        }
    }

    @Test
    fun `no template contains a semicolon or colon`() {
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertFalse("$emotion 的模板不应含分号或冒号",
                text.any { it == ';' || it == '；' || it == ':' || it == '：' })
        }
    }

    @Test
    fun `non-happy templates end with a period`() {
        Emotion.entries.filter { it != Emotion.HAPPY }.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            assertTrue("$emotion 的模板应以句号结尾", text.last() == '。' || text.last() == '.')
        }
    }


    @Test
    fun `only anxious template mentions worry`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("担心") || text.contains("worry", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.ANXIOUS), naming)
    }

    @Test
    fun `only anxious template mentions sorting things out`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("事情") || text.contains("sort it out", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.ANXIOUS), naming)
    }

    @Test
    fun `only neutral template says got it`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("收到") || text.contains("Got it")
        }
        assertEquals(listOf(Emotion.NEUTRAL), naming)
    }

    @Test
    fun `only sad template mentions talking`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("聊聊") || text.contains("talk", ignoreCase = true)
        }
        assertEquals(listOf(Emotion.SAD), naming)
    }

    @Test
    fun `happy template is shorter than neutral template`() {
        val happy = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        val neutral = AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        assertTrue(happy.length < neutral.length)
    }


    @Test
    fun `only happy template says great`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            text.contains("太好了") || text.contains("great")
        }
        assertEquals(listOf(Emotion.HAPPY), naming)
    }

    @Test
    fun `only sad template pairs understanding with an open invitation`() {
        val naming = Emotion.entries.filter {
            val text = AiEmotionFallback.localFallback(context, it)
            (text.contains("理解") || text.contains("understand")) &&
                (text.contains("随时") || text.contains("whenever"))
        }
        assertEquals(listOf(Emotion.SAD), naming)
    }

    @Test
    fun `happy template has two clauses`() {
        val text = AiEmotionFallback.localFallback(context, Emotion.HAPPY)
        assertEquals(2, text.split('，', ',').size)
    }

    @Test
    fun `angry template has three clauses`() {
        val text = AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        assertEquals(3, text.split('，', ',').size)
    }

    @Test
    fun `no template starts with punctuation or whitespace`() {
        val marks = setOf('，', ',', '。', '.', '！', '!', '？', '?')
        Emotion.entries.forEach { emotion ->
            val text = AiEmotionFallback.localFallback(context, emotion)
            val first = text.first()
            assertFalse("$emotion 的模板不应以标点或空白开头", first.isWhitespace() || first in marks)
        }
    }

    @Test
    fun `sad and angry templates share the full understanding phrase`() {
        val sad = AiEmotionFallback.localFallback(context, Emotion.SAD)
        val angry = AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        assertTrue(sad.contains("I understand how you feel"))
        assertTrue(angry.contains("I understand how you feel"))
        assertFalse(AiEmotionFallback.localFallback(context, Emotion.HAPPY).contains("I understand how you feel"))
        assertFalse(AiEmotionFallback.localFallback(context, Emotion.ANXIOUS).contains("I understand how you feel"))
        assertFalse(AiEmotionFallback.localFallback(context, Emotion.NEUTRAL).contains("I understand how you feel"))
    }

    @Test
    fun `neutral template has three clauses`() {
        val text = AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        assertEquals(3, text.split(',').size)
    }

    @Test
    fun `anxious template has two apostrophes and others have at most one`() {
        val counts = Emotion.entries.associateWith { emotion ->
            AiEmotionFallback.localFallback(context, emotion).count { it == '\'' }
        }
        assertEquals(2, counts[Emotion.ANXIOUS])
        assertTrue(counts.filterKeys { it != Emotion.ANXIOUS }.values.all { it <= 1 })
    }

    @Test
    fun `sad and anxious templates tie for the most words`() {
        val counts = Emotion.entries.associateWith { emotion ->
            AiEmotionFallback.localFallback(context, emotion).split(" ").size
        }
        assertEquals(12, counts[Emotion.SAD])
        assertEquals(12, counts[Emotion.ANXIOUS])
        assertTrue(counts[Emotion.HAPPY]!! < 12 && counts[Emotion.NEUTRAL]!! < 12 && counts[Emotion.ANGRY]!! < 12)
    }

    @Test
    fun `happy and neutral templates have the two smallest word counts`() {
        val counts = Emotion.entries.associateWith { emotion ->
            AiEmotionFallback.localFallback(context, emotion).split(" ").size
        }
        assertEquals(5, counts[Emotion.HAPPY])
        assertEquals(6, counts[Emotion.NEUTRAL])
        assertTrue(counts[Emotion.SAD]!! > 6 && counts[Emotion.ANGRY]!! > 6 && counts[Emotion.ANXIOUS]!! > 6)
    }

    @Test
    fun `english templates have exact lengths in emotion order`() {
        val lengths = Emotion.entries.map { AiEmotionFallback.localFallback(context, it).length }
        assertEquals(listOf(28, 62, 56, 63, 32), lengths)
    }

    @Test
    fun `angry anxious and neutral templates have exactly two commas`() {
        val twoComma = Emotion.entries.filter {
            AiEmotionFallback.localFallback(context, it).count { c -> c == ',' } == 2
        }
        assertEquals(listOf(Emotion.ANGRY, Emotion.ANXIOUS, Emotion.NEUTRAL), twoComma)
    }

    @Test
    fun `word counts are five twelve eleven twelve six in emotion order`() {
        val counts = Emotion.entries.map {
            AiEmotionFallback.localFallback(context, it).split(" ").size
        }
        assertEquals(listOf(5, 12, 11, 12, 6), counts)
    }

    @Test
    fun `only sad template addresses you twice`() {
        val counts = Emotion.entries.associateWith { emotion ->
            AiEmotionFallback.localFallback(context, emotion)
                .split(" ").count { it.trim(',', '.', '!') == "you" }
        }
        assertEquals(2, counts[Emotion.SAD])
        assertEquals(1, counts[Emotion.HAPPY])
        assertEquals(1, counts[Emotion.ANGRY])
        assertEquals(0, counts[Emotion.ANXIOUS])
        assertEquals(0, counts[Emotion.NEUTRAL])
    }

    @Test
    fun `only sad and angry templates mention feeling`() {
        val withFeel = Emotion.entries.filter {
            AiEmotionFallback.localFallback(context, it).contains("feel")
        }
        assertEquals(listOf(Emotion.SAD, Emotion.ANGRY), withFeel)
    }

    @Test
    fun `angry template opens with take it easy`() {
        val angry = AiEmotionFallback.localFallback(context, Emotion.ANGRY)
        assertTrue(angry.startsWith("Take it easy,"))
        Emotion.entries.filter { it != Emotion.ANGRY }.forEach { emotion ->
            assertFalse(AiEmotionFallback.localFallback(context, emotion).startsWith("Take"))
        }
    }

    @Test
    fun `anxious template holds both contractions`() {
        val withDont = Emotion.entries.filter {
            AiEmotionFallback.localFallback(context, it).contains("Don't")
        }
        val withLets = Emotion.entries.filter {
            AiEmotionFallback.localFallback(context, it).contains("let's")
        }
        assertEquals(listOf(Emotion.ANXIOUS), withDont)
        assertEquals(listOf(Emotion.ANXIOUS), withLets)
    }

    @Test
    fun `every template first word is distinct and capitalized`() {
        val firstWords = Emotion.entries.map {
            AiEmotionFallback.localFallback(context, it).split(" ").first()
        }
        assertEquals(listOf("That's", "I", "Take", "Don't", "Got"), firstWords)
        assertTrue(firstWords.all { it.first().isUpperCase() })
    }

    @Test
    fun `only happy template ends with you exclamation`() {
        Emotion.entries.forEach { emotion ->
            assertEquals(
                emotion == Emotion.HAPPY,
                AiEmotionFallback.localFallback(context, emotion).endsWith("you!")
            )
        }
    }

    @Test
    fun `neutral template opens with got it and ends with listening`() {
        val neutral = AiEmotionFallback.localFallback(context, Emotion.NEUTRAL)
        assertTrue(neutral.startsWith("Got it,"))
        assertTrue(neutral.endsWith("listening."))
        Emotion.entries.filter { it != Emotion.NEUTRAL }.forEach { emotion ->
            assertFalse(AiEmotionFallback.localFallback(context, emotion).startsWith("Got it,"))
        }
    }

}
