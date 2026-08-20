package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群玩法 round-trip 第二批：单值 format/parse 对。
 * 9.225 修复了 17 处「用户输入段未 esc」的截断 bug（内容含 | 时被切断），
 * 本批测试锁定修复后的契约，并对纯随机/归一化输入验证 round-trip。
 */
class GroupPlayPolicyRoundTrip2Test {

    private val host = "Host"

    @Test
    fun `coin flip normalizes side and round trips`() {
        assertEquals("HEADS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("heads", host)))
        assertEquals("TAILS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("tails", host)))
        // 非法输入归一为 TAILS
        assertEquals("TAILS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("sideways", host)))
    }

    @Test
    fun `charades prompt with separator char survives esc fix`() {
        val tricky = "act|out this"
        assertEquals(tricky, GroupPlayPolicy.parseCharades(GroupPlayPolicy.formatCharades(tricky, host)))
        // 空白回退
        assertEquals("mystery", GroupPlayPolicy.parseCharades(GroupPlayPolicy.formatCharades("  ", host)))
    }

    @Test
    fun `number guess round trips secret and max`() {
        assertEquals(42 to 100, GroupPlayPolicy.parseNumberGuess(GroupPlayPolicy.formatNumberGuess(42, 100, host)))
    }

    @Test
    fun `riddle round trips q and a with separator chars`() {
        val parsed = GroupPlayPolicy.parseRiddle(GroupPlayPolicy.formatRiddle("q|with pipe", "a^answer", host))
        assertEquals("q|with pipe", parsed?.first)
        assertEquals("a^answer", parsed?.second)
    }

    @Test
    fun `impostor emoji story simon hotornot round trip`() {
        assertEquals("apple", GroupPlayPolicy.parseImpostor(GroupPlayPolicy.formatImpostor("apple", host)))
        assertEquals("🚀✨", GroupPlayPolicy.parseEmojiStory(GroupPlayPolicy.formatEmojiStory("🚀✨", host)))
        assertEquals("ABAB", GroupPlayPolicy.parseSimon(GroupPlayPolicy.formatSimon("ABAB", host)))
        assertEquals("pizza", GroupPlayPolicy.parseHotOrNot(GroupPlayPolicy.formatHotOrNot("pizza", host)))
    }

    @Test
    fun `alphabet normalizes to single uppercase letter`() {
        assertEquals("B", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("b", host)))
        assertEquals("X", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("xyz", host)))
        assertEquals("A", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("", host)))
    }

    @Test
    fun `trivia round trips q and a`() {
        val parsed = GroupPlayPolicy.parseTrivia(GroupPlayPolicy.formatTrivia("Q|1", "A|1", host))
        assertEquals("Q|1", parsed?.first)
        assertEquals("A|1", parsed?.second)
    }

    @Test
    fun `speed challenge clamps seconds and round trips`() {
        assertEquals(30, GroupPlayPolicy.parseSpeedChallenge(GroupPlayPolicy.formatSpeedChallenge(30, host)))
        assertEquals(5, GroupPlayPolicy.parseSpeedChallenge(GroupPlayPolicy.formatSpeedChallenge(1, host)))
        assertEquals(60, GroupPlayPolicy.parseSpeedChallenge(GroupPlayPolicy.formatSpeedChallenge(999, host)))
    }

    @Test
    fun `truth or dare round trips mode and prompt with pipe`() {
        val parsed = GroupPlayPolicy.parseTruthOrDare(GroupPlayPolicy.formatTruthOrDare("DARE", "do|10 pushups", host))
        assertEquals("dare", parsed?.first)
        assertEquals("do|10 pushups", parsed?.second)
        // 非法 mode 归一为 truth
        assertEquals("truth", GroupPlayPolicy.parseTruthOrDare(GroupPlayPolicy.formatTruthOrDare("weird", "p", host))?.first)
    }

    @Test
    fun `never have fortune emoji quiz draw icebreaker duel round trip with separators`() {
        assertEquals("never|done", GroupPlayPolicy.parseNeverHaveIEver(GroupPlayPolicy.formatNeverHaveIEver("never|done", host)))
        assertEquals("good|luck", GroupPlayPolicy.parseFortune(GroupPlayPolicy.formatFortune("good|luck", host)))
        val quiz = GroupPlayPolicy.parseEmojiQuiz(GroupPlayPolicy.formatEmojiQuiz("🍎|📱", "apple|phone", host))
        assertEquals("🍎|📱", quiz?.first)
        assertEquals("apple|phone", quiz?.second)
        assertEquals("cat|dog", GroupPlayPolicy.parseDrawPrompt(GroupPlayPolicy.formatDrawPrompt("cat|dog", host)))
        assertEquals("two|truths", GroupPlayPolicy.parseIcebreaker(GroupPlayPolicy.formatIcebreaker("two|truths", host)))
        assertEquals("🔥|❄️", GroupPlayPolicy.parseEmojiDuel(GroupPlayPolicy.formatEmojiDuel("🔥|❄️", host)))
    }

    @Test
    fun `parse rejects foreign prefixes batch`() {
        assertNull(GroupPlayPolicy.parseCoinFlip("RIDDLE:x|y"))
        assertNull(GroupPlayPolicy.parseCharades(""))
        assertNull(GroupPlayPolicy.parseImpostor("SIMON:x|y"))
        assertNull(GroupPlayPolicy.parseTrivia("TRIVIA:onlyonepart"))
    }

    @Test
    fun `lightning whisper and late games round trip`() {
        assertEquals("fast|topic", GroupPlayPolicy.parseLightning(GroupPlayPolicy.formatLightning("fast|topic", host)))
        assertEquals("quiet|word", GroupPlayPolicy.parseWhisper(GroupPlayPolicy.formatWhisper("quiet|word", host)))
        assertEquals("🎭|only", GroupPlayPolicy.parseEmojiOnly(GroupPlayPolicy.formatEmojiOnly("🎭|only", host)))
        assertEquals("mime|it", GroupPlayPolicy.parseSilentMovie(GroupPlayPolicy.formatSilentMovie("mime|it", host)))
        assertEquals("red|blue", GroupPlayPolicy.parseColorWord(GroupPlayPolicy.formatColorWord("red|blue", host)))
        assertEquals("scram|bled", GroupPlayPolicy.parseWordScramble(GroupPlayPolicy.formatWordScramble("scram|bled", host)))
        assertEquals("👍|👎", GroupPlayPolicy.parseReactionDuel(GroupPlayPolicy.formatReactionDuel("👍|👎", host)))
        assertTrue(GroupPlayPolicy.parseTranslateRelay(GroupPlayPolicy.formatTranslateRelay("hola|amigo", host)) != null)
        assertTrue(GroupPlayPolicy.parseGratitudeRound(GroupPlayPolicy.formatGratitudeRound("thanks|all", host)) != null)
    }
}
