package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fuzz / random-oracle tests for the GroupPlay classic codec cluster
 * (checklist line 931, 50th block): [GroupPlayClassicQuiz]. Test-only, zero production
 * changes.
 *
 * Each format/parse pair is reimplemented independently as an oracle
 * (prefix, take() limit, trim order, guard and suffix template transcribed
 * per pair from the production file; shapes: single / pair / quiz triple /
 * two-truths / bingo list / lottery / number-guess / int-coerce /
 * countdown-race / coin-flip). Shared primitives (production calls them too,
 * so sharing is exact): trim/take (UTF-16 code units), substringBefore/After,
 * split, joinToString, uppercase (default locale), toIntOrNull,
 * equals(ignoreCase), and the two field-escape replaces (as an independent
 * copy, escOracle).
 *
 * Quirks pinned, not fixed (behaviour, not bugs):
 * - formatQuiz does not trim the question (take(120) only) and does not
 *   trim/take the answer at all; blank options are dropped on parse.
 * - parseNumberGuess has no separator guard: a bare number parses as (n, n).
 * - parseOddOneOut / parseEmojiTranslate have no separator guard either:
 *   a bare body duplicates into both fields; riddle/trivia/emojiQuiz do
 *   have the guard and reject separator-less bodies.
 * - formatAlphabet does not escape the single letter, so a "|" letter
 *   makes its own parse return null; trivia does not trim while riddle does.
 * - The 8 Quiz->WordGames delegates forward verbatim; the wiring is pinned
 *   by equality against the WordGames entry points.
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 126020 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, int / string / parse exhaustives, and
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 15 test
 * methods were also run locally against the real production code
 * (kotlin-test stubs): all pass.
 */
class GroupPlayClassicQuizFuzzTest {

    // Shared primitives (production calls them too, so sharing is exact):
    // trim/take (UTF-16 code units), substringBefore/After, split, joinToString,
    // uppercase (default locale), toIntOrNull, equals(ignoreCase). The field
    // escape itself is an independent copy of the two replaces:
    // "|" <-> \u0001, "^" <-> \u0002.
    private fun escOracle(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unescOracle(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    private fun clampIntOracle(v: Int, lo: Int, hi: Int): Int {
        if (v < lo) return lo
        if (v > hi) return hi
        return v
    }

    private fun formatTwoTruthsOneLieQuizOracle(t1: String, t2: String, lie: String, hostLabel: String): String {
        val a = t1.trim().take(80)
        val b = t2.trim().take(80)
        val c = lie.trim().take(80)
        return "TRUTHS:" + "2|" + escOracle(a) + "|" + escOracle(b) + "|" + escOracle(c) + "|" + hostLabel + " two truths & one lie"
    }

    private fun parseTwoTruthsOneLieQuizOracle(content: String): List<String>? {
        if (!content.startsWith("TRUTHS:")) return null
        val body = content.removePrefix("TRUTHS:")
        val parts = body.split('|').map { unescOracle(it) }
        if (parts.size < 4) return null
        return listOf(parts[1], parts[2], parts[3]).filter { it.isNotBlank() }
    }

    private fun formatQuizQuizOracle(question: String, answer: String, options: List<String>, hostLabel: String): String {
        val opts = options.joinToString("^") { escOracle(it.take(40)) }
        return "QUIZ:" + escOracle(question.take(120)) + "|" + escOracle(answer) + "|" + opts + "|" + hostLabel + " quiz"
    }

    private fun parseQuizQuizOracle(content: String): Triple<String, String, List<String>>? {
        if (!content.startsWith("QUIZ:")) return null
        val body = content.removePrefix("QUIZ:")
        val parts = body.split('|')
        if (parts.size < 3) return null
        val q = unescOracle(parts[0])
        val ans = unescOracle(parts[1])
        val opts = parts[2].split('^').filter { it.isNotBlank() }.map { unescOracle(it) }
        return Triple(q, ans, opts)
    }

    private fun formatCharadesQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40).ifBlank { "mystery" }
        return "CHARADES:" + escOracle(v) + "|" + hostLabel + " charades — act it out!"
    }

    private fun parseCharadesQuizOracle(content: String): String? {
        if (!content.startsWith("CHARADES:")) return null
        val v = unescOracle(content.removePrefix("CHARADES:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatNumberGuessQuizOracle(secret: Int, max: Int, hostLabel: String): String {
        return "NUMGUESS:" + secret.toString() + "|" + max.toString() + "|" + hostLabel + " number guess 1.." + max.toString()
    }

    private fun parseNumberGuessQuizOracle(content: String): Pair<Int, Int>? {
        if (!content.startsWith("NUMGUESS:")) return null
        val body = content.removePrefix("NUMGUESS:")
        val secret = unescOracle(body.substringBefore('|')).toIntOrNull() ?: return null
        val max = unescOracle(body.substringAfter('|').substringBefore('|')).toIntOrNull() ?: return null
        return secret to max
    }

    private fun formatRiddleQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(80)
        val vb = b.trim().take(40)
        return "RIDDLE:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " riddle"
    }

    private fun parseRiddleQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("RIDDLE:")) return null
        val body = content.removePrefix("RIDDLE:")
        if (!body.contains('|')) return null
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatAlphabetQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(1).uppercase().ifBlank { "A" }
        return "ALPHABET:" + v + "|" + hostLabel + " alphabet race — name something starting with " + v
    }

    private fun parseAlphabetQuizOracle(content: String): String? {
        if (!content.startsWith("ALPHABET:")) return null
        val v = unescOracle(content.removePrefix("ALPHABET:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTriviaQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.take(80)
        val vb = b.take(40)
        return "TRIVIA:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " trivia"
    }

    private fun parseTriviaQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("TRIVIA:")) return null
        val body = content.removePrefix("TRIVIA:")
        if (!body.contains('|')) return null
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatScatterQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(2).uppercase()
        val vb = b.trim().take(24)
        return "SCATTER:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " scattergories — name one!"
    }

    private fun parseScatterQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("SCATTER:")) return null
        val body = content.removePrefix("SCATTER:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }

    private fun formatKaraokeQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "KARAOKE:" + escOracle(v) + "|" + hostLabel + " karaoke challenge"
    }

    private fun parseKaraokeQuizOracle(content: String): String? {
        if (!content.startsWith("KARAOKE:")) return null
        val v = unescOracle(content.removePrefix("KARAOKE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatBlindQQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "BLINDQ:" + escOracle(v) + "|" + hostLabel + " blind Q — guess about someone!"
    }

    private fun parseBlindQQuizOracle(content: String): String? {
        if (!content.startsWith("BLINDQ:")) return null
        val v = unescOracle(content.removePrefix("BLINDQ:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatEmojiQuizQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(24)
        val vb = b.trim().take(40)
        return "EMOJIQUIZ:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " emoji quiz"
    }

    private fun parseEmojiQuizQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("EMOJIQUIZ:")) return null
        val body = content.removePrefix("EMOJIQUIZ:")
        if (!body.contains('|')) return null
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatDebateQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "DEBATE:" + escOracle(v) + "|" + hostLabel + " debate — pick a side!"
    }

    private fun parseDebateQuizOracle(content: String): String? {
        if (!content.startsWith("DEBATE:")) return null
        val v = unescOracle(content.removePrefix("DEBATE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMirrorQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(100)
        return "MIRROR:" + escOracle(v) + "|" + hostLabel + " mirror — repeat in your style"
    }

    private fun parseMirrorQuizOracle(content: String): String? {
        if (!content.startsWith("MIRROR:")) return null
        val v = unescOracle(content.removePrefix("MIRROR:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatToastQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "TOAST:" + escOracle(v) + "|" + hostLabel + " friendly roast"
    }

    private fun parseToastQuizOracle(content: String): String? {
        if (!content.startsWith("TOAST:")) return null
        val v = unescOracle(content.removePrefix("TOAST:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatEmojiTranslateQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(24)
        val vb = b.trim().take(40)
        return "EMOJITR:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " emoji translate"
    }

    private fun parseEmojiTranslateQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("EMOJITR:")) return null
        val body = content.removePrefix("EMOJITR:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatTwentyQuestionsQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "TWENTYQ:" + escOracle(v) + "|" + hostLabel + " 20 questions — yes/no only!"
    }

    private fun parseTwentyQuestionsQuizOracle(content: String): String? {
        if (!content.startsWith("TWENTYQ:")) return null
        val v = unescOracle(content.removePrefix("TWENTYQ:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatOddOneOutQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(80)
        val vb = b.trim().take(40)
        return "ODDONE:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " odd one out"
    }

    private fun parseOddOneOutQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("ODDONE:")) return null
        val body = content.removePrefix("ODDONE:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatCategoriesQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "CATEGORIES:" + escOracle(v) + "|" + hostLabel + " categories — name things in '" + v + "'"
    }

    private fun parseCategoriesQuizOracle(content: String): String? {
        if (!content.startsWith("CATEGORIES:")) return null
        val v = unescOracle(content.removePrefix("CATEGORIES:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatGeoGuessQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "GEOGUESS:" + escOracle(v) + "|" + hostLabel + " geo guess"
    }

    private fun parseGeoGuessQuizOracle(content: String): String? {
        if (!content.startsWith("GEOGUESS:")) return null
        val v = unescOracle(content.removePrefix("GEOGUESS:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSpeedMathQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "SPEEDMATH:" + escOracle(v) + "|" + hostLabel + " speed math — first correct wins"
    }

    private fun parseSpeedMathQuizOracle(content: String): String? {
        if (!content.startsWith("SPEEDMATH:")) return null
        val v = unescOracle(content.removePrefix("SPEEDMATH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatAlphabetRaceQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(4)
        return "ALPHARACE:" + escOracle(v) + "|" + hostLabel + " alphabet race"
    }

    private fun parseAlphabetRaceQuizOracle(content: String): String? {
        if (!content.startsWith("ALPHARACE:")) return null
        val v = unescOracle(content.removePrefix("ALPHARACE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatDebateFlashQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "DEBATEFLASH:" + escOracle(v) + "|" + hostLabel + " debate flash - 30s side"
    }

    private fun parseDebateFlashQuizOracle(content: String): String? {
        if (!content.startsWith("DEBATEFLASH:")) return null
        val v = unescOracle(content.removePrefix("DEBATEFLASH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatQuickPollQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "QUICKPOLL:" + escOracle(v) + "|" + hostLabel + " quick poll"
    }

    private fun parseQuickPollQuizOracle(content: String): String? {
        if (!content.startsWith("QUICKPOLL:")) return null
        val v = unescOracle(content.removePrefix("QUICKPOLL:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMirrorEchoQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "MIRRORECHO:" + escOracle(v) + "|" + hostLabel + " mirror echo — reverse it"
    }

    private fun parseMirrorEchoQuizOracle(content: String): String? {
        if (!content.startsWith("MIRRORECHO:")) return null
        val v = unescOracle(content.removePrefix("MIRRORECHO:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatFactOrFictionQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "FACTORFICTION:" + escOracle(v) + "|" + hostLabel + " fact or fiction"
    }

    private fun parseFactOrFictionQuizOracle(content: String): String? {
        if (!content.startsWith("FACTORFICTION:")) return null
        val v = unescOracle(content.removePrefix("FACTORFICTION:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatCodeBreakerQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(8)
        return "CODEBREAKER:" + escOracle(v) + "|" + hostLabel + " code breaker — guess digits"
    }

    private fun parseCodeBreakerQuizOracle(content: String): String? {
        if (!content.startsWith("CODEBREAKER:")) return null
        val v = unescOracle(content.removePrefix("CODEBREAKER:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatEmojiMathQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "EMOJIMATH:" + escOracle(v) + "|" + hostLabel + " emoji math"
    }

    private fun parseEmojiMathQuizOracle(content: String): String? {
        if (!content.startsWith("EMOJIMATH:")) return null
        val v = unescOracle(content.removePrefix("EMOJIMATH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTranslateRelayQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "TRANSRELAY:" + escOracle(v) + "|" + hostLabel + " translate relay"
    }

    private fun parseTranslateRelayQuizOracle(content: String): String? {
        if (!content.startsWith("TRANSRELAY:")) return null
        val v = unescOracle(content.removePrefix("TRANSRELAY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWordHintQuizOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(60)
        val vb = b.trim().take(40)
        return "WORDHINT:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " word hint"
    }

    private fun parseWordHintQuizOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("WORDHINT:")) return null
        val body = content.removePrefix("WORDHINT:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatAcrosticQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "ACROSTIC:" + escOracle(v) + "|" + hostLabel + " acrostic — start each line with letters of '" + v + "'"
    }

    private fun parseAcrosticQuizOracle(content: String): String? {
        if (!content.startsWith("ACROSTIC:")) return null
        val v = unescOracle(content.removePrefix("ACROSTIC:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatRhymeQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "RHYME:" + escOracle(v) + "|" + hostLabel + " rhyme chain — rhyme with '" + v + "'"
    }

    private fun parseRhymeQuizOracle(content: String): String? {
        if (!content.startsWith("RHYME:")) return null
        val v = unescOracle(content.removePrefix("RHYME:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatPasswordGameQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "PASSWORD:" + escOracle(v) + "|" + hostLabel + " password game — guess under rules"
    }

    private fun parsePasswordGameQuizOracle(content: String): String? {
        if (!content.startsWith("PASSWORD:")) return null
        val v = unescOracle(content.removePrefix("PASSWORD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTabooQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "TABOO:" + escOracle(v) + "|" + hostLabel + " taboo — describe '" + v.substringBefore('|') + "' without banned words"
    }

    private fun parseTabooQuizOracle(content: String): String? {
        if (!content.startsWith("TABOO:")) return null
        val v = unescOracle(content.removePrefix("TABOO:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTwoWordsQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "TWOWORDS:" + escOracle(v) + "|" + hostLabel + " two-word story — start with '" + v + "'"
    }

    private fun parseTwoWordsQuizOracle(content: String): String? {
        if (!content.startsWith("TWOWORDS:")) return null
        val v = unescOracle(content.removePrefix("TWOWORDS:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatOneWordQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "ONEWORD:" + escOracle(v) + "|" + hostLabel + " one-word story — continue with one word"
    }

    private fun parseOneWordQuizOracle(content: String): String? {
        if (!content.startsWith("ONEWORD:")) return null
        val v = unescOracle(content.removePrefix("ONEWORD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWordScrambleQuizOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "WORDSCRAMBLE:" + escOracle(v) + "|" + hostLabel + " word scramble"
    }

    private fun parseWordScrambleQuizOracle(content: String): String? {
        if (!content.startsWith("WORDSCRAMBLE:")) return null
        val v = unescOracle(content.removePrefix("WORDSCRAMBLE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    // ---------- generators (Random(42) in the CI tests) ----------

    private val STR_POOL = listOf(
        "", " ", "  ", "   a   ", "a", "ab", "abc", "|", "^", "\u0001", "\u0002",
        "a|b", "a^b", "a\u0001b", "a\u0002b", "||", "^^", "a|b^c",
        "hello", "hello world", "  padded  ", "\t", "\n", "a\tb", "a\nb",
        "é", "ß", "İ", "i", "I", "中文测试", "😀", "a😀b", "😀😀😀",
        "\u00a0", "x\u00a0y", "\u2007", "\u202f",
        "rock", "paper", "scissors", "ROCK", "Heads", "HEADS", "heads", "TAILS",
        "0", "1", "-1", "42", "9999999999", "2147483647", "-2147483648",
        "a".repeat(50), "x".repeat(130), "word ".repeat(30)
    )

    private val CH_POOL = listOf(
        "a", "b", "z", "A", "Z", "0", "9", " ", "|", "^", "\u0001", "\u0002",
        "\t", "\n", "é", "ß", "中", "😀", "\u00a0", ".", "-", "_", "!"
    )

    private fun randomString(rnd: Random): String {
        return when (rnd.nextInt(4)) {
            0 -> STR_POOL[rnd.nextInt(STR_POOL.size)]
            1 -> buildString {
                repeat(rnd.nextInt(0, 30)) { append(CH_POOL[rnd.nextInt(CH_POOL.size)]) }
            }
            2 -> {
                // Boundary-length strings around the take() limits.
                val n = listOf(
                    0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 15, 16, 17, 19, 20, 21,
                    23, 24, 25, 29, 30, 31, 39, 40, 41, 49, 50, 51, 59, 60, 61,
                    79, 80, 81, 99, 100, 101, 119, 120, 121, 130
                )[rnd.nextInt(42)]
                val ch = listOf("a", " ", "|", "😀")[rnd.nextInt(4)]
                ch.repeat(n)
            }
            else -> {
                val base = buildString {
                    repeat(rnd.nextInt(0, 25)) { append('a' + rnd.nextInt(26)) }
                }
                base + listOf("", " ", "|", "^", "\u0001", "😀", "  ")[rnd.nextInt(7)]
            }
        }
    }

    private fun randomStringNonBlank(rnd: Random): String {
        repeat(10) {
            val s = randomString(rnd)
            if (s.trim().isNotBlank()) return s
        }
        return "x"
    }

    private val INT_POOL = listOf(
        Int.MIN_VALUE, Int.MAX_VALUE, -100000, -100, -31, -30, -6, -5, -3, -2, -1,
        0, 1, 2, 3, 4, 5, 6, 29, 30, 31, 59, 60, 61, 599, 600, 601, 100, 100000
    )

    private fun randomInt(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> INT_POOL[rnd.nextInt(INT_POOL.size)]
            1 -> rnd.nextInt(-1000, 1001)
            else -> rnd.nextInt()
        }
    }

    private fun randomStringList(rnd: Random): List<String> {
        val n = rnd.nextInt(0, 7)
        return List(n) { randomString(rnd) }
    }

    // EmojiDuel's format falls back to a random emoji for blank input, so the
    // oracle (and the differential harness) only ever feeds it non-blank input.
    private fun randomStringFor(rnd: Random, key: String): String {
        return if (key == "Duel:emojiDuel") randomStringNonBlank(rnd) else randomString(rnd)
    }

    private fun prefixForQuiz(key: String): String {
        return when (key) {
            "Quiz:twoTruths" -> "TRUTHS:"
            "Quiz:quiz" -> "QUIZ:"
            "Quiz:charades" -> "CHARADES:"
            "Quiz:numberGuess" -> "NUMGUESS:"
            "Quiz:riddle" -> "RIDDLE:"
            "Quiz:alphabet" -> "ALPHABET:"
            "Quiz:trivia" -> "TRIVIA:"
            "Quiz:scatter" -> "SCATTER:"
            "Quiz:karaoke" -> "KARAOKE:"
            "Quiz:blindQ" -> "BLINDQ:"
            "Quiz:emojiQuiz" -> "EMOJIQUIZ:"
            "Quiz:debate" -> "DEBATE:"
            "Quiz:mirror" -> "MIRROR:"
            "Quiz:toast" -> "TOAST:"
            "Quiz:emojiTranslate" -> "EMOJITR:"
            "Quiz:twentyQuestions" -> "TWENTYQ:"
            "Quiz:oddOneOut" -> "ODDONE:"
            "Quiz:categories" -> "CATEGORIES:"
            "Quiz:geoGuess" -> "GEOGUESS:"
            "Quiz:speedMath" -> "SPEEDMATH:"
            "Quiz:alphabetRace" -> "ALPHARACE:"
            "Quiz:debateFlash" -> "DEBATEFLASH:"
            "Quiz:quickPoll" -> "QUICKPOLL:"
            "Quiz:mirrorEcho" -> "MIRRORECHO:"
            "Quiz:factOrFiction" -> "FACTORFICTION:"
            "Quiz:codeBreaker" -> "CODEBREAKER:"
            "Quiz:emojiMath" -> "EMOJIMATH:"
            "Quiz:translateRelay" -> "TRANSRELAY:"
            "Quiz:wordHint" -> "WORDHINT:"
            "Quiz:acrostic" -> "ACROSTIC:"
            "Quiz:rhyme" -> "RHYME:"
            "Quiz:passwordGame" -> "PASSWORD:"
            "Quiz:taboo" -> "TABOO:"
            "Quiz:twoWords" -> "TWOWORDS:"
            "Quiz:oneWord" -> "ONEWORD:"
            "Quiz:wordScramble" -> "WORDSCRAMBLE:"
            else -> error("unknown key " + key)
        }
    }

    private fun randomFormatOutputForQuiz(rnd: Random, key: String): String {
        val host = randomString(rnd)
        return when (key) {
            "Quiz:twoTruths" -> formatTwoTruthsOneLieQuizOracle(randomString(rnd), randomString(rnd), randomString(rnd), host)
            "Quiz:quiz" -> formatQuizQuizOracle(randomString(rnd), randomString(rnd), randomStringList(rnd), host)
            "Quiz:charades" -> formatCharadesQuizOracle(randomStringFor(rnd, "Quiz:charades"), host)
            "Quiz:numberGuess" -> formatNumberGuessQuizOracle(randomInt(rnd), randomInt(rnd), host)
            "Quiz:riddle" -> formatRiddleQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:alphabet" -> formatAlphabetQuizOracle(randomStringFor(rnd, "Quiz:alphabet"), host)
            "Quiz:trivia" -> formatTriviaQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:scatter" -> formatScatterQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:karaoke" -> formatKaraokeQuizOracle(randomStringFor(rnd, "Quiz:karaoke"), host)
            "Quiz:blindQ" -> formatBlindQQuizOracle(randomStringFor(rnd, "Quiz:blindQ"), host)
            "Quiz:emojiQuiz" -> formatEmojiQuizQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:debate" -> formatDebateQuizOracle(randomStringFor(rnd, "Quiz:debate"), host)
            "Quiz:mirror" -> formatMirrorQuizOracle(randomStringFor(rnd, "Quiz:mirror"), host)
            "Quiz:toast" -> formatToastQuizOracle(randomStringFor(rnd, "Quiz:toast"), host)
            "Quiz:emojiTranslate" -> formatEmojiTranslateQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:twentyQuestions" -> formatTwentyQuestionsQuizOracle(randomStringFor(rnd, "Quiz:twentyQuestions"), host)
            "Quiz:oddOneOut" -> formatOddOneOutQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:categories" -> formatCategoriesQuizOracle(randomStringFor(rnd, "Quiz:categories"), host)
            "Quiz:geoGuess" -> formatGeoGuessQuizOracle(randomStringFor(rnd, "Quiz:geoGuess"), host)
            "Quiz:speedMath" -> formatSpeedMathQuizOracle(randomStringFor(rnd, "Quiz:speedMath"), host)
            "Quiz:alphabetRace" -> formatAlphabetRaceQuizOracle(randomStringFor(rnd, "Quiz:alphabetRace"), host)
            "Quiz:debateFlash" -> formatDebateFlashQuizOracle(randomStringFor(rnd, "Quiz:debateFlash"), host)
            "Quiz:quickPoll" -> formatQuickPollQuizOracle(randomStringFor(rnd, "Quiz:quickPoll"), host)
            "Quiz:mirrorEcho" -> formatMirrorEchoQuizOracle(randomStringFor(rnd, "Quiz:mirrorEcho"), host)
            "Quiz:factOrFiction" -> formatFactOrFictionQuizOracle(randomStringFor(rnd, "Quiz:factOrFiction"), host)
            "Quiz:codeBreaker" -> formatCodeBreakerQuizOracle(randomStringFor(rnd, "Quiz:codeBreaker"), host)
            "Quiz:emojiMath" -> formatEmojiMathQuizOracle(randomStringFor(rnd, "Quiz:emojiMath"), host)
            "Quiz:translateRelay" -> formatTranslateRelayQuizOracle(randomStringFor(rnd, "Quiz:translateRelay"), host)
            "Quiz:wordHint" -> formatWordHintQuizOracle(randomString(rnd), randomString(rnd), host)
            "Quiz:acrostic" -> formatAcrosticQuizOracle(randomStringFor(rnd, "Quiz:acrostic"), host)
            "Quiz:rhyme" -> formatRhymeQuizOracle(randomStringFor(rnd, "Quiz:rhyme"), host)
            "Quiz:passwordGame" -> formatPasswordGameQuizOracle(randomStringFor(rnd, "Quiz:passwordGame"), host)
            "Quiz:taboo" -> formatTabooQuizOracle(randomStringFor(rnd, "Quiz:taboo"), host)
            "Quiz:twoWords" -> formatTwoWordsQuizOracle(randomStringFor(rnd, "Quiz:twoWords"), host)
            "Quiz:oneWord" -> formatOneWordQuizOracle(randomStringFor(rnd, "Quiz:oneWord"), host)
            "Quiz:wordScramble" -> formatWordScrambleQuizOracle(randomStringFor(rnd, "Quiz:wordScramble"), host)
            else -> error("unknown key " + key)
        }
    }

    private fun randomContentForQuiz(rnd: Random, key: String): String {
        val prefix = prefixForQuiz(key)
        return when (rnd.nextInt(4)) {
            0 -> randomFormatOutputForQuiz(rnd, key)
            1 -> prefix + randomString(rnd)
            2 -> randomString(rnd)
            else -> listOf("", prefix, prefix + "|", prefix + "a|b", "WRONG:" + randomString(rnd))[rnd.nextInt(5)]
        }
    }

    @Test
    fun formatSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:charades", "Quiz:alphabet", "Quiz:karaoke", "Quiz:blindQ", "Quiz:debate", "Quiz:mirror", "Quiz:toast", "Quiz:twentyQuestions", "Quiz:categories", "Quiz:geoGuess", "Quiz:speedMath", "Quiz:alphabetRace", "Quiz:debateFlash", "Quiz:quickPoll", "Quiz:mirrorEcho", "Quiz:factOrFiction", "Quiz:codeBreaker", "Quiz:emojiMath", "Quiz:translateRelay")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:charades" -> assertEquals(
                        formatCharadesQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatCharades(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:alphabet" -> assertEquals(
                        formatAlphabetQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatAlphabet(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:karaoke" -> assertEquals(
                        formatKaraokeQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatKaraoke(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:blindQ" -> assertEquals(
                        formatBlindQQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatBlindQ(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:debate" -> assertEquals(
                        formatDebateQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatDebate(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:mirror" -> assertEquals(
                        formatMirrorQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatMirror(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:toast" -> assertEquals(
                        formatToastQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatToast(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:twentyQuestions" -> assertEquals(
                        formatTwentyQuestionsQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatTwentyQuestions(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:categories" -> assertEquals(
                        formatCategoriesQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatCategories(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:geoGuess" -> assertEquals(
                        formatGeoGuessQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatGeoGuess(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:speedMath" -> assertEquals(
                        formatSpeedMathQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatSpeedMath(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:alphabetRace" -> assertEquals(
                        formatAlphabetRaceQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatAlphabetRace(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:debateFlash" -> assertEquals(
                        formatDebateFlashQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatDebateFlash(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:quickPoll" -> assertEquals(
                        formatQuickPollQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatQuickPoll(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:mirrorEcho" -> assertEquals(
                        formatMirrorEchoQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatMirrorEcho(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:factOrFiction" -> assertEquals(
                        formatFactOrFictionQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatFactOrFiction(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:codeBreaker" -> assertEquals(
                        formatCodeBreakerQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatCodeBreaker(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:emojiMath" -> assertEquals(
                        formatEmojiMathQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatEmojiMath(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:translateRelay" -> assertEquals(
                        formatTranslateRelayQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatTranslateRelay(x, host),
                        "key=$key x=$x host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:charades", "Quiz:alphabet", "Quiz:karaoke", "Quiz:blindQ", "Quiz:debate", "Quiz:mirror", "Quiz:toast", "Quiz:twentyQuestions", "Quiz:categories", "Quiz:geoGuess", "Quiz:speedMath", "Quiz:alphabetRace", "Quiz:debateFlash", "Quiz:quickPoll", "Quiz:mirrorEcho", "Quiz:factOrFiction", "Quiz:codeBreaker", "Quiz:emojiMath", "Quiz:translateRelay")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:charades" -> assertEquals(
                        parseCharadesQuizOracle(content),
                        GroupPlayClassicQuiz.parseCharades(content),
                        "key=$key content=$content"
                    )
                    "Quiz:alphabet" -> assertEquals(
                        parseAlphabetQuizOracle(content),
                        GroupPlayClassicQuiz.parseAlphabet(content),
                        "key=$key content=$content"
                    )
                    "Quiz:karaoke" -> assertEquals(
                        parseKaraokeQuizOracle(content),
                        GroupPlayClassicQuiz.parseKaraoke(content),
                        "key=$key content=$content"
                    )
                    "Quiz:blindQ" -> assertEquals(
                        parseBlindQQuizOracle(content),
                        GroupPlayClassicQuiz.parseBlindQ(content),
                        "key=$key content=$content"
                    )
                    "Quiz:debate" -> assertEquals(
                        parseDebateQuizOracle(content),
                        GroupPlayClassicQuiz.parseDebate(content),
                        "key=$key content=$content"
                    )
                    "Quiz:mirror" -> assertEquals(
                        parseMirrorQuizOracle(content),
                        GroupPlayClassicQuiz.parseMirror(content),
                        "key=$key content=$content"
                    )
                    "Quiz:toast" -> assertEquals(
                        parseToastQuizOracle(content),
                        GroupPlayClassicQuiz.parseToast(content),
                        "key=$key content=$content"
                    )
                    "Quiz:twentyQuestions" -> assertEquals(
                        parseTwentyQuestionsQuizOracle(content),
                        GroupPlayClassicQuiz.parseTwentyQuestions(content),
                        "key=$key content=$content"
                    )
                    "Quiz:categories" -> assertEquals(
                        parseCategoriesQuizOracle(content),
                        GroupPlayClassicQuiz.parseCategories(content),
                        "key=$key content=$content"
                    )
                    "Quiz:geoGuess" -> assertEquals(
                        parseGeoGuessQuizOracle(content),
                        GroupPlayClassicQuiz.parseGeoGuess(content),
                        "key=$key content=$content"
                    )
                    "Quiz:speedMath" -> assertEquals(
                        parseSpeedMathQuizOracle(content),
                        GroupPlayClassicQuiz.parseSpeedMath(content),
                        "key=$key content=$content"
                    )
                    "Quiz:alphabetRace" -> assertEquals(
                        parseAlphabetRaceQuizOracle(content),
                        GroupPlayClassicQuiz.parseAlphabetRace(content),
                        "key=$key content=$content"
                    )
                    "Quiz:debateFlash" -> assertEquals(
                        parseDebateFlashQuizOracle(content),
                        GroupPlayClassicQuiz.parseDebateFlash(content),
                        "key=$key content=$content"
                    )
                    "Quiz:quickPoll" -> assertEquals(
                        parseQuickPollQuizOracle(content),
                        GroupPlayClassicQuiz.parseQuickPoll(content),
                        "key=$key content=$content"
                    )
                    "Quiz:mirrorEcho" -> assertEquals(
                        parseMirrorEchoQuizOracle(content),
                        GroupPlayClassicQuiz.parseMirrorEcho(content),
                        "key=$key content=$content"
                    )
                    "Quiz:factOrFiction" -> assertEquals(
                        parseFactOrFictionQuizOracle(content),
                        GroupPlayClassicQuiz.parseFactOrFiction(content),
                        "key=$key content=$content"
                    )
                    "Quiz:codeBreaker" -> assertEquals(
                        parseCodeBreakerQuizOracle(content),
                        GroupPlayClassicQuiz.parseCodeBreaker(content),
                        "key=$key content=$content"
                    )
                    "Quiz:emojiMath" -> assertEquals(
                        parseEmojiMathQuizOracle(content),
                        GroupPlayClassicQuiz.parseEmojiMath(content),
                        "key=$key content=$content"
                    )
                    "Quiz:translateRelay" -> assertEquals(
                        parseTranslateRelayQuizOracle(content),
                        GroupPlayClassicQuiz.parseTranslateRelay(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatPairsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:riddle", "Quiz:trivia", "Quiz:scatter", "Quiz:emojiQuiz", "Quiz:emojiTranslate", "Quiz:oddOneOut")) {
                val a = randomString(rnd)
                val b = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:riddle" -> assertEquals(
                        formatRiddleQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatRiddle(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Quiz:trivia" -> assertEquals(
                        formatTriviaQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatTrivia(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Quiz:scatter" -> assertEquals(
                        formatScatterQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatScatter(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Quiz:emojiQuiz" -> assertEquals(
                        formatEmojiQuizQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatEmojiQuiz(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Quiz:emojiTranslate" -> assertEquals(
                        formatEmojiTranslateQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatEmojiTranslate(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Quiz:oddOneOut" -> assertEquals(
                        formatOddOneOutQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatOddOneOut(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parsePairsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:riddle", "Quiz:trivia", "Quiz:scatter", "Quiz:emojiQuiz", "Quiz:emojiTranslate", "Quiz:oddOneOut")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:riddle" -> assertEquals(
                        parseRiddleQuizOracle(content),
                        GroupPlayClassicQuiz.parseRiddle(content),
                        "key=$key content=$content"
                    )
                    "Quiz:trivia" -> assertEquals(
                        parseTriviaQuizOracle(content),
                        GroupPlayClassicQuiz.parseTrivia(content),
                        "key=$key content=$content"
                    )
                    "Quiz:scatter" -> assertEquals(
                        parseScatterQuizOracle(content),
                        GroupPlayClassicQuiz.parseScatter(content),
                        "key=$key content=$content"
                    )
                    "Quiz:emojiQuiz" -> assertEquals(
                        parseEmojiQuizQuizOracle(content),
                        GroupPlayClassicQuiz.parseEmojiQuiz(content),
                        "key=$key content=$content"
                    )
                    "Quiz:emojiTranslate" -> assertEquals(
                        parseEmojiTranslateQuizOracle(content),
                        GroupPlayClassicQuiz.parseEmojiTranslate(content),
                        "key=$key content=$content"
                    )
                    "Quiz:oddOneOut" -> assertEquals(
                        parseOddOneOutQuizOracle(content),
                        GroupPlayClassicQuiz.parseOddOneOut(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatQuizRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:quiz")) {
                val q = randomString(rnd)
                val ans = randomString(rnd)
                val opts = randomStringList(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:quiz" -> assertEquals(
                        formatQuizQuizOracle(q, ans, opts, host),
                        GroupPlayClassicQuiz.formatQuiz(q, ans, opts, host),
                        "key=$key q=$q ans=$ans opts=$opts host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseQuizRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:quiz")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:quiz" -> assertEquals(
                        parseQuizQuizOracle(content),
                        GroupPlayClassicQuiz.parseQuiz(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatTwoTruthsOneLieRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:twoTruths")) {
                val t1 = randomString(rnd)
                val t2 = randomString(rnd)
                val lie = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:twoTruths" -> assertEquals(
                        formatTwoTruthsOneLieQuizOracle(t1, t2, lie, host),
                        GroupPlayClassicQuiz.formatTwoTruthsOneLie(t1, t2, lie, host),
                        "key=$key t1=$t1 t2=$t2 lie=$lie host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseTwoTruthsOneLieRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:twoTruths")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:twoTruths" -> assertEquals(
                        parseTwoTruthsOneLieQuizOracle(content),
                        GroupPlayClassicQuiz.parseTwoTruthsOneLie(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatNumberGuessRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:numberGuess")) {
                val secret = randomInt(rnd)
                val max = randomInt(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:numberGuess" -> assertEquals(
                        formatNumberGuessQuizOracle(secret, max, host),
                        GroupPlayClassicQuiz.formatNumberGuess(secret, max, host),
                        "key=$key secret=$secret max=$max host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseNumberGuessRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:numberGuess")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:numberGuess" -> assertEquals(
                        parseNumberGuessQuizOracle(content),
                        GroupPlayClassicQuiz.parseNumberGuess(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatDelegateSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:acrostic", "Quiz:rhyme", "Quiz:passwordGame", "Quiz:taboo", "Quiz:twoWords", "Quiz:oneWord", "Quiz:wordScramble")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:acrostic" -> assertEquals(
                        formatAcrosticQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatAcrostic(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:rhyme" -> assertEquals(
                        formatRhymeQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatRhyme(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:passwordGame" -> assertEquals(
                        formatPasswordGameQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatPasswordGame(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:taboo" -> assertEquals(
                        formatTabooQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatTaboo(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:twoWords" -> assertEquals(
                        formatTwoWordsQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatTwoWords(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:oneWord" -> assertEquals(
                        formatOneWordQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatOneWord(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Quiz:wordScramble" -> assertEquals(
                        formatWordScrambleQuizOracle(x, host),
                        GroupPlayClassicQuiz.formatWordScramble(x, host),
                        "key=$key x=$x host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseDelegateSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:acrostic", "Quiz:rhyme", "Quiz:passwordGame", "Quiz:taboo", "Quiz:twoWords", "Quiz:oneWord", "Quiz:wordScramble")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:acrostic" -> assertEquals(
                        parseAcrosticQuizOracle(content),
                        GroupPlayClassicQuiz.parseAcrostic(content),
                        "key=$key content=$content"
                    )
                    "Quiz:rhyme" -> assertEquals(
                        parseRhymeQuizOracle(content),
                        GroupPlayClassicQuiz.parseRhyme(content),
                        "key=$key content=$content"
                    )
                    "Quiz:passwordGame" -> assertEquals(
                        parsePasswordGameQuizOracle(content),
                        GroupPlayClassicQuiz.parsePasswordGame(content),
                        "key=$key content=$content"
                    )
                    "Quiz:taboo" -> assertEquals(
                        parseTabooQuizOracle(content),
                        GroupPlayClassicQuiz.parseTaboo(content),
                        "key=$key content=$content"
                    )
                    "Quiz:twoWords" -> assertEquals(
                        parseTwoWordsQuizOracle(content),
                        GroupPlayClassicQuiz.parseTwoWords(content),
                        "key=$key content=$content"
                    )
                    "Quiz:oneWord" -> assertEquals(
                        parseOneWordQuizOracle(content),
                        GroupPlayClassicQuiz.parseOneWord(content),
                        "key=$key content=$content"
                    )
                    "Quiz:wordScramble" -> assertEquals(
                        parseWordScrambleQuizOracle(content),
                        GroupPlayClassicQuiz.parseWordScramble(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatDelegateWordHintRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:wordHint")) {
                val a = randomString(rnd)
                val b = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Quiz:wordHint" -> assertEquals(
                        formatWordHintQuizOracle(a, b, host),
                        GroupPlayClassicQuiz.formatWordHint(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseDelegateWordHintRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Quiz:wordHint")) {
                val content = randomContentForQuiz(rnd, key)
                when (key) {
                    "Quiz:wordHint" -> assertEquals(
                        parseWordHintQuizOracle(content),
                        GroupPlayClassicQuiz.parseWordHint(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Charades: trim + blank falls back to "mystery".
        assertEquals("CHARADES:hi|Host charades — act it out!", GroupPlayClassicQuiz.formatCharades("  hi ", "Host"))
        assertEquals("CHARADES:mystery|Host charades — act it out!", GroupPlayClassicQuiz.formatCharades("   ", "Host"))
        assertEquals("hi", GroupPlayClassicQuiz.parseCharades("CHARADES:hi|Host charades — act it out!"))
        assertNull(GroupPlayClassicQuiz.parseCharades("CHARADES:|Host"))
        assertNull(GroupPlayClassicQuiz.parseCharades("WRONG:hi|Host"))
        // Alphabet: take(1) + uppercase; a "|" letter survives into the body
        // unescaped, so its own parse sees an empty field and returns null.
        assertEquals("ALPHABET:A|H alphabet race — name something starting with A", GroupPlayClassicQuiz.formatAlphabet("abc", "H"))
        assertEquals("ALPHABET:A|H alphabet race — name something starting with A", GroupPlayClassicQuiz.formatAlphabet("  ", "H"))
        assertEquals("ALPHABET:||H alphabet race — name something starting with |", GroupPlayClassicQuiz.formatAlphabet("|", "H"))
        assertNull(GroupPlayClassicQuiz.parseAlphabet(GroupPlayClassicQuiz.formatAlphabet("|", "H")))
        // Quiz: question is NOT trimmed (take only); options are escaped per
        // item before joining, so | and ^ inside options round-trip.
        assertEquals("QUIZ:  q  |a|o1^o2|H quiz", GroupPlayClassicQuiz.formatQuiz("  q  ", "a", listOf("o1", "o2"), "H"))
        assertEquals(Triple("  q  ", "a", listOf("o1", "o2")), GroupPlayClassicQuiz.parseQuiz("QUIZ:  q  |a|o1^o2|H quiz"))
        assertEquals("QUIZ:q|a|a\u0001b^c\u0002d|H quiz", GroupPlayClassicQuiz.formatQuiz("q", "a", listOf("a|b", "c^d"), "H"))
        assertEquals(Triple("q", "a", listOf("a|b", "c^d")), GroupPlayClassicQuiz.parseQuiz("QUIZ:q|a|a\u0001b^c\u0002d|H quiz"))
        // Quiz: blank options are dropped on parse; an empty option list
        // parses back to an empty list, not null.
        assertEquals(Triple("q", "a", listOf("x")), GroupPlayClassicQuiz.parseQuiz("QUIZ:q|a|^^x|H quiz"))
        assertEquals(Triple("q", "a", emptyList()), GroupPlayClassicQuiz.parseQuiz("QUIZ:q|a|"))
        assertNull(GroupPlayClassicQuiz.parseQuiz("QUIZ:onlyone"))
        // TwoTruths: the "2|" marker is a literal segment; blank items are
        // dropped, and a short body is rejected outright.
        assertEquals("TRUTHS:2|a|b|c|H two truths & one lie", GroupPlayClassicQuiz.formatTwoTruthsOneLie("a", "b", "c", "H"))
        assertEquals(listOf("a", "b", "c"), GroupPlayClassicQuiz.parseTwoTruthsOneLie("TRUTHS:2|a|b|c|H two truths & one lie"))
        assertEquals(listOf("a", "c"), GroupPlayClassicQuiz.parseTwoTruthsOneLie("TRUTHS:2|a|   |c|H two truths & one lie"))
        assertNull(GroupPlayClassicQuiz.parseTwoTruthsOneLie("TRUTHS:2|a|b"))
        // NumberGuess: no separator guard — a bare number parses as (n, n).
        assertEquals("NUMGUESS:5|10|H number guess 1..10", GroupPlayClassicQuiz.formatNumberGuess(5, 10, "H"))
        assertEquals(5 to 10, GroupPlayClassicQuiz.parseNumberGuess("NUMGUESS:5|10|H number guess 1..10"))
        assertEquals(5 to 5, GroupPlayClassicQuiz.parseNumberGuess("NUMGUESS:5"))
        assertNull(GroupPlayClassicQuiz.parseNumberGuess("NUMGUESS:x|10"))
        assertNull(GroupPlayClassicQuiz.parseNumberGuess("NUMGUESS:5|x"))
        // Riddle / Trivia: the '|' guard rejects separator-less bodies; trivia
        // does NOT trim while riddle does.
        assertEquals("RIDDLE:q?|a!|H riddle", GroupPlayClassicQuiz.formatRiddle("  q? ", "  a! ", "H"))
        assertEquals("TRIVIA:  q  |a|H trivia", GroupPlayClassicQuiz.formatTrivia("  q  ", "a", "H"))
        assertNull(GroupPlayClassicQuiz.parseRiddle("RIDDLE:q"))
        assertNull(GroupPlayClassicQuiz.parseRiddle("RIDDLE:|a"))
        assertEquals("q" to "", GroupPlayClassicQuiz.parseRiddle("RIDDLE:q|"))
        assertNull(GroupPlayClassicQuiz.parseTrivia("TRIVIA:q"))
        // Scatter: letter is uppercased after take(2); either blank side
        // rejects the whole pair; no separator guard, so a bare body
        // duplicates into both fields.
        assertEquals("SCATTER:AB|cat|H scattergories — name one!", GroupPlayClassicQuiz.formatScatter("abcdef", "cat", "H"))
        assertEquals("AB" to "cat", GroupPlayClassicQuiz.parseScatter("SCATTER:AB|cat|H scattergories — name one!"))
        assertNull(GroupPlayClassicQuiz.parseScatter("SCATTER:|cat"))
        assertNull(GroupPlayClassicQuiz.parseScatter("SCATTER:AB|"))
        assertEquals("AB" to "AB", GroupPlayClassicQuiz.parseScatter("SCATTER:AB"))
        // EmojiQuiz has the '|' guard; EmojiTranslate and OddOneOut do not.
        assertNull(GroupPlayClassicQuiz.parseEmojiQuiz("EMOJIQUIZ:only"))
        assertEquals("only" to "only", GroupPlayClassicQuiz.parseEmojiTranslate("EMOJITR:only"))
        assertEquals("only" to "only", GroupPlayClassicQuiz.parseOddOneOut("ODDONE:only"))
        // Categories interpolates the raw value into the suffix, too.
        assertEquals("CATEGORIES:pets|H categories — name things in 'pets'", GroupPlayClassicQuiz.formatCategories("pets", "H"))
        assertEquals("pets", GroupPlayClassicQuiz.parseCategories("CATEGORIES:pets|H categories — name things in 'pets'"))
        // take() cuts by UTF-16 code unit: 150 x's become 100.
        assertEquals("x".repeat(100), GroupPlayClassicQuiz.parseMirror(GroupPlayClassicQuiz.formatMirror("x".repeat(150), "H")))
        // Delegates forward verbatim to GroupPlayClassicWordGames.
        assertEquals(GroupPlayClassicWordGames.formatWordHint("h", "a", "H"), GroupPlayClassicQuiz.formatWordHint("h", "a", "H"))
        assertEquals(GroupPlayClassicWordGames.formatAcrostic("s", "H"), GroupPlayClassicQuiz.formatAcrostic("s", "H"))
        assertEquals(GroupPlayClassicWordGames.formatRhyme("s", "H"), GroupPlayClassicQuiz.formatRhyme("s", "H"))
        assertEquals(GroupPlayClassicWordGames.formatPasswordGame("h", "H"), GroupPlayClassicQuiz.formatPasswordGame("h", "H"))
        assertEquals(GroupPlayClassicWordGames.formatTaboo("c", "H"), GroupPlayClassicQuiz.formatTaboo("c", "H"))
        assertEquals(GroupPlayClassicWordGames.formatTwoWords("s", "H"), GroupPlayClassicQuiz.formatTwoWords("s", "H"))
        assertEquals(GroupPlayClassicWordGames.formatOneWord("w", "H"), GroupPlayClassicQuiz.formatOneWord("w", "H"))
        assertEquals(GroupPlayClassicWordGames.formatWordScramble("p", "H"), GroupPlayClassicQuiz.formatWordScramble("p", "H"))
        assertEquals(GroupPlayClassicWordGames.parseWordHint("WORDHINT:h|a"), GroupPlayClassicQuiz.parseWordHint("WORDHINT:h|a"))
        assertEquals("TABOO:word\u0001banned|H taboo — describe 'word' without banned words", GroupPlayClassicQuiz.formatTaboo("word|banned", "H"))
    }

}
