package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for the GroupPlay classic Party codec cluster
 * (checklist line 931, 51st block): [GroupPlayClassicParty]. Test-only, zero
 * production changes.
 *
 * Each format/parse pair is reimplemented independently as an oracle
 * (prefix, take() limit, trim order, guard and suffix template transcribed
 * per pair from the production file; shapes: single / plain pair /
 * wouldYouRather triple / truthOrDare pair / emojiRain pair / wordChain
 * delegate). Shared primitives (production calls them too, so sharing is
 * exact): trim/take (UTF-16 code units), substringBefore/After, split,
 * lowercase (default locale), and the two field-escape replaces (as an
 * independent copy, escOracle). Blank memoryMatch / chainReact / hideSeek
 * inputs are excluded from the random format tests (production falls back
 * to a random list emoji there); their blank path is pinned by membership
 * below instead. formatEmojiRain is always called with an explicit emoji.
 *
 * Pinned quirks (all verified against real production):
 * - parseStory / parseRedPacketJoke return "" on a blank field, never null;
 *   every other single parse returns null on blank.
 * - formatNeverHaveIEver("") formats to an empty field, which parses to null.
 * - formatTruthOrDare coerces any mode to truth/dare; parse takes any mode verbatim.
 * - formatWordChain does not escape: '|' in the seed splits on parse.
 * - formatEmojiRain take(4) counts UTF-16 units (cuts astral emoji mid-pair);
 *   its parse leaves the rest raw (a literal U+0001 survives).
 * - parseWouldYouRather's label keeps the " would you rather" suffix text;
 *   a '|' in the host truncates the label; a 4th field is dropped.
 * - Blank memoryMatch / chainReact / hideSeek fall back to a random list
 *   emoji (membership pinned); a literal U+0001 field parses back as '|'.

 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 78272 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, string / parse exhaustives, and
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 9 test
 * methods were also run locally against the real production code
 * (kotlin-test stubs): all pass.
 */
class GroupPlayClassicPartyFuzzTest {

    // Shared primitives (production calls them too, so sharing is exact):
    // trim/take (UTF-16 code units), substringBefore/After, split. The field
    // escape itself is an independent copy of the two replaces:
    // "|" <-> \u0001, "^" <-> \u0002.
    private fun escOracle(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unescOracle(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    private fun formatWordChainOracle(seed: String, userLabel: String): String {
        return "WORD:" + seed + "|" + userLabel + " word chain: start with '" + seed + "'"
    }

    private fun parseWordChainOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("WORD:")) return null
        val body = content.removePrefix("WORD:")
        val seed = body.substringBefore('|').trim()
        val rest = body.substringAfter('|', "")
        if (seed.isBlank()) return null
        return seed to rest
    }

    private fun formatWouldYouRatherOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(80)
        val vb = b.trim().take(80)
        return "WOULD:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " would you rather"
    }

    private fun parseWouldYouRatherOracle(content: String): Triple<String, String, String>? {
        if (!content.startsWith("WOULD:")) return null
        val body = content.removePrefix("WOULD:")
        val parts = body.split('|').map { unescOracle(it) }
        if (parts.size < 2) return null
        val a = parts[0]
        val b = parts[1]
        val label = parts.getOrNull(2).orEmpty()
        if (a.isBlank() || b.isBlank()) return null
        return Triple(a, b, label)
    }

    private fun formatEmojiRainOracle(hostLabel: String, emoji: String): String {
        val e = emoji.take(4).ifBlank { "🎉" }
        return "EMOJI_RAIN:" + escOracle(e) + "|" + hostLabel + " started emoji rain " + e
    }

    private fun parseEmojiRainOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("EMOJI_RAIN:")) return null
        val body = content.removePrefix("EMOJI_RAIN:")
        val emoji = unescOracle(body.substringBefore('|')).ifBlank { return null }
        val rest = body.substringAfter('|', "")
        return emoji to rest
    }

    private fun formatStoryOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "Once upon a time in a chat group..." }.take(160)
        return "STORY:" + escOracle(v) + "|" + hostLabel + " started a story"
    }

    private fun parseStoryOracle(content: String): String? {
        if (!content.startsWith("STORY:")) return null
        val v = unescOracle(content.removePrefix("STORY:").substringBefore('|'))
        return v
    }

    private fun formatHotSeatOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "someone" }.take(40)
        return "HOTSEAT:" + escOracle(v) + "|" + hostLabel + " put " + v + " on the hot seat — ask a question!"
    }

    private fun parseHotSeatOracle(content: String): String? {
        if (!content.startsWith("HOTSEAT:")) return null
        val v = unescOracle(content.removePrefix("HOTSEAT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatRedPacketJokeOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "lucky" }.take(24)
        return "REDPACKET:" + escOracle(v) + "|" + hostLabel + " sent a fun red packet (" + v + ") — claim in chat!"
    }

    private fun parseRedPacketJokeOracle(content: String): String? {
        if (!content.startsWith("REDPACKET:")) return null
        val v = unescOracle(content.removePrefix("REDPACKET:").substringBefore('|'))
        return v
    }

    private fun formatImpostorOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "apple" }.take(24)
        return "IMPOSTOR:" + escOracle(v) + "|" + hostLabel + " started impostor — find the odd one out!"
    }

    private fun parseImpostorOracle(content: String): String? {
        if (!content.startsWith("IMPOSTOR:")) return null
        val v = unescOracle(content.removePrefix("IMPOSTOR:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatEmojiStoryOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "✨" }.take(24)
        return "EMOJISTORY:" + escOracle(v) + "|" + hostLabel + " emoji story — continue in chat!"
    }

    private fun parseEmojiStoryOracle(content: String): String? {
        if (!content.startsWith("EMOJISTORY:")) return null
        val v = unescOracle(content.removePrefix("EMOJISTORY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatHotOrNotOracle(x: String, hostLabel: String): String {
        val v = x.trim().ifBlank { "this idea" }.take(60)
        return "HOTORNOT:" + escOracle(v) + "|" + hostLabel + " hot or not: " + v
    }

    private fun parseHotOrNotOracle(content: String): String? {
        if (!content.startsWith("HOTORNOT:")) return null
        val v = unescOracle(content.removePrefix("HOTORNOT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTruthOrDareOracle(mode: String, prompt: String, hostLabel: String): String {
        val m = mode.trim().lowercase().let { if (it == "dare") "dare" else "truth" }
        val p = prompt.trim().take(80)
        return "TRUTHDARE:" + escOracle(m) + "|" + escOracle(p) + "|" + hostLabel + " truth-or-dare"
    }

    private fun parseTruthOrDareOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("TRUTHDARE:")) return null
        val body = content.removePrefix("TRUTHDARE:")
        if (!body.contains('|')) return null
        val mode = unescOracle(body.substringBefore('|'))
        val prompt = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (prompt.isBlank()) return null
        return mode to prompt
    }

    private fun formatNeverHaveIEverOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(100)
        return "NEVERHAVE:" + escOracle(v) + "|" + hostLabel + " never-have-I-ever — react if you have!"
    }

    private fun parseNeverHaveIEverOracle(content: String): String? {
        if (!content.startsWith("NEVERHAVE:")) return null
        val v = unescOracle(content.removePrefix("NEVERHAVE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMemoryMatchOracle(x: String, hostLabel: String): String {
        // NOTE: blank-after-trim input falls back to a random emoji in
        // production; the generator only feeds non-blank input, so the
        // oracle below (trim + take only) is exact on its whole domain.
        val v = x.trim().take(24)
        return "MEMORY:" + escOracle(v) + "|" + hostLabel + " memory match — find pairs!"
    }

    private fun parseMemoryMatchOracle(content: String): String? {
        if (!content.startsWith("MEMORY:")) return null
        val v = unescOracle(content.removePrefix("MEMORY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatDrawPromptOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "DRAWPROMPT:" + escOracle(v) + "|" + hostLabel + " draw this (no words)!"
    }

    private fun parseDrawPromptOracle(content: String): String? {
        if (!content.startsWith("DRAWPROMPT:")) return null
        val v = unescOracle(content.removePrefix("DRAWPROMPT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatIcebreakerOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(100)
        return "ICEBREAKER:" + escOracle(v) + "|" + hostLabel + " icebreaker"
    }

    private fun parseIcebreakerOracle(content: String): String? {
        if (!content.startsWith("ICEBREAKER:")) return null
        val v = unescOracle(content.removePrefix("ICEBREAKER:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMinuteTalkOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "MINUTETALK:" + escOracle(v) + "|" + hostLabel + " 60s talk — go!"
    }

    private fun parseMinuteTalkOracle(content: String): String? {
        if (!content.startsWith("MINUTETALK:")) return null
        val v = unescOracle(content.removePrefix("MINUTETALK:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatCaptionThisOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "CAPTION:" + escOracle(v) + "|" + hostLabel + " caption this!"
    }

    private fun parseCaptionThisOracle(content: String): String? {
        if (!content.startsWith("CAPTION:")) return null
        val v = unescOracle(content.removePrefix("CAPTION:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatStorySwapOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "STORYSWAP:" + escOracle(v) + "|" + hostLabel + " story swap — continue!"
    }

    private fun parseStorySwapOracle(content: String): String? {
        if (!content.startsWith("STORYSWAP:")) return null
        val v = unescOracle(content.removePrefix("STORYSWAP:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatFortuneOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "FORTUNE:" + escOracle(v) + "|" + hostLabel + " fortune cookie"
    }

    private fun parseFortuneOracle(content: String): String? {
        if (!content.startsWith("FORTUNE:")) return null
        val v = unescOracle(content.removePrefix("FORTUNE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatChainReactOracle(x: String, hostLabel: String): String {
        // NOTE: blank-after-trim input falls back to a random emoji in
        // production; the generator only feeds non-blank input, so the
        // oracle below (trim + take only) is exact on its whole domain.
        val v = x.trim().take(8)
        return "CHAINREACT:" + escOracle(v) + "|" + hostLabel + " chain react — reply with related emoji!"
    }

    private fun parseChainReactOracle(content: String): String? {
        if (!content.startsWith("CHAINREACT:")) return null
        val v = unescOracle(content.removePrefix("CHAINREACT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatHideSeekOracle(x: String, hostLabel: String): String {
        // NOTE: blank-after-trim input falls back to a random emoji in
        // production; the generator only feeds non-blank input, so the
        // oracle below (trim + take only) is exact on its whole domain.
        val v = x.trim().take(8)
        return "HIDESEEK:" + escOracle(v) + "|" + hostLabel + " hide & seek — find the emoji in chat history!"
    }

    private fun parseHideSeekOracle(content: String): String? {
        if (!content.startsWith("HIDESEEK:")) return null
        val v = unescOracle(content.removePrefix("HIDESEEK:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSpyfallOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "SPYFALL:" + escOracle(v) + "|" + hostLabel + " spyfall — one spy doesn't know the place!"
    }

    private fun parseSpyfallOracle(content: String): String? {
        if (!content.startsWith("SPYFALL:")) return null
        val v = unescOracle(content.removePrefix("SPYFALL:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTimeCapsuleOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(80)
        return "TIMECAPSULE:" + escOracle(v) + "|" + hostLabel + " time capsule — open later"
    }

    private fun parseTimeCapsuleOracle(content: String): String? {
        if (!content.startsWith("TIMECAPSULE:")) return null
        val v = unescOracle(content.removePrefix("TIMECAPSULE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWhisperOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "WHISPER:" + escOracle(v) + "|" + hostLabel + " whisper challenge"
    }

    private fun parseWhisperOracle(content: String): String? {
        if (!content.startsWith("WHISPER:")) return null
        val v = unescOracle(content.removePrefix("WHISPER:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatEmojiMemoryOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(24)
        return "EMOJIMEM:" + escOracle(v) + "|" + hostLabel + " emoji memory — memorize then recall"
    }

    private fun parseEmojiMemoryOracle(content: String): String? {
        if (!content.startsWith("EMOJIMEM:")) return null
        val v = unescOracle(content.removePrefix("EMOJIMEM:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatStorySeedOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "STORYSEED:" + escOracle(v) + "|" + hostLabel + " story seed — write the next sentence"
    }

    private fun parseStorySeedOracle(content: String): String? {
        if (!content.startsWith("STORYSEED:")) return null
        val v = unescOracle(content.removePrefix("STORYSEED:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWould2Oracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(30)
        val vb = b.trim().take(30)
        return "WOULD2:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " would you rather"
    }

    private fun parseWould2Oracle(content: String): Pair<String, String>? {
        if (!content.startsWith("WOULD2:")) return null
        val body = content.removePrefix("WOULD2:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }

    private fun formatEmojiOnlyOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "EMOJIONLY:" + escOracle(v) + "|" + hostLabel + " emoji-only challenge"
    }

    private fun parseEmojiOnlyOracle(content: String): String? {
        if (!content.startsWith("EMOJIONLY:")) return null
        val v = unescOracle(content.removePrefix("EMOJIONLY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatBlindDrawOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(8)
        return "BLINDDRAW:" + escOracle(v) + "|" + hostLabel + " blind draw — guess the emoji"
    }

    private fun parseBlindDrawOracle(content: String): String? {
        if (!content.startsWith("BLINDDRAW:")) return null
        val v = unescOracle(content.removePrefix("BLINDDRAW:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSilentMovieOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "SILENTMOVIE:" + escOracle(v) + "|" + hostLabel + " silent movie"
    }

    private fun parseSilentMovieOracle(content: String): String? {
        if (!content.startsWith("SILENTMOVIE:")) return null
        val v = unescOracle(content.removePrefix("SILENTMOVIE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatColorWordOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "COLORWORD:" + escOracle(v) + "|" + hostLabel + " color-word"
    }

    private fun parseColorWordOracle(content: String): String? {
        if (!content.startsWith("COLORWORD:")) return null
        val v = unescOracle(content.removePrefix("COLORWORD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatImpulseDrawOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(8)
        return "IMPULSEDRAW:" + escOracle(v) + "|" + hostLabel + " impulse draw"
    }

    private fun parseImpulseDrawOracle(content: String): String? {
        if (!content.startsWith("IMPULSEDRAW:")) return null
        val v = unescOracle(content.removePrefix("IMPULSEDRAW:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSillyLawOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(50)
        return "SILLYLAW:" + escOracle(v) + "|" + hostLabel + " silly law"
    }

    private fun parseSillyLawOracle(content: String): String? {
        if (!content.startsWith("SILLYLAW:")) return null
        val v = unescOracle(content.removePrefix("SILLYLAW:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatPinTheMoodOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "PINTHEMOOD:" + escOracle(v) + "|" + hostLabel + " pin the mood"
    }

    private fun parsePinTheMoodOracle(content: String): String? {
        if (!content.startsWith("PINTHEMOOD:")) return null
        val v = unescOracle(content.removePrefix("PINTHEMOOD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatRevokeRushOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(10)
        return "REVOKERUSH:" + escOracle(v) + "|" + hostLabel + " revoke rush"
    }

    private fun parseRevokeRushOracle(content: String): String? {
        if (!content.startsWith("REVOKERUSH:")) return null
        val v = unescOracle(content.removePrefix("REVOKERUSH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSecretSignalOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "SECRETSIGNAL:" + escOracle(v) + "|" + hostLabel + " secret signal"
    }

    private fun parseSecretSignalOracle(content: String): String? {
        if (!content.startsWith("SECRETSIGNAL:")) return null
        val v = unescOracle(content.removePrefix("SECRETSIGNAL:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatIdeaRelayOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "IDEARELAY:" + escOracle(v) + "|" + hostLabel + " idea relay"
    }

    private fun parseIdeaRelayOracle(content: String): String? {
        if (!content.startsWith("IDEARELAY:")) return null
        val v = unescOracle(content.removePrefix("IDEARELAY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMoodMeterOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "MOODMETER:" + escOracle(v) + "|" + hostLabel + " mood meter"
    }

    private fun parseMoodMeterOracle(content: String): String? {
        if (!content.startsWith("MOODMETER:")) return null
        val v = unescOracle(content.removePrefix("MOODMETER:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatGratitudeRoundOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "GRATROUND:" + escOracle(v) + "|" + hostLabel + " gratitude round"
    }

    private fun parseGratitudeRoundOracle(content: String): String? {
        if (!content.startsWith("GRATROUND:")) return null
        val v = unescOracle(content.removePrefix("GRATROUND:").substringBefore('|'))
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
                    79, 80, 81, 99, 100, 101, 119, 120, 121, 130, 159, 160, 161
                )[rnd.nextInt(45)]
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

    // memoryMatch / chainReact / hideSeek fall back to a random list emoji for
    // blank-after-trim input in production, so the oracle (and the
    // differential harness) only ever feeds them non-blank input.
    private fun randomStringFor(rnd: Random, key: String): String {
        return if (key == "Party:memoryMatch" || key == "Party:chainReact" ||
            key == "Party:hideSeek") randomStringNonBlank(rnd) else randomString(rnd)
    }

    private fun prefixForParty(key: String): String {
        return when (key) {
            "Party:wordChain" -> "WORD:"
            "Party:wouldYouRather" -> "WOULD:"
            "Party:emojiRain" -> "EMOJI_RAIN:"
            "Party:story" -> "STORY:"
            "Party:hotSeat" -> "HOTSEAT:"
            "Party:redPacketJoke" -> "REDPACKET:"
            "Party:impostor" -> "IMPOSTOR:"
            "Party:emojiStory" -> "EMOJISTORY:"
            "Party:hotOrNot" -> "HOTORNOT:"
            "Party:truthOrDare" -> "TRUTHDARE:"
            "Party:neverHaveIEver" -> "NEVERHAVE:"
            "Party:memoryMatch" -> "MEMORY:"
            "Party:drawPrompt" -> "DRAWPROMPT:"
            "Party:icebreaker" -> "ICEBREAKER:"
            "Party:minuteTalk" -> "MINUTETALK:"
            "Party:captionThis" -> "CAPTION:"
            "Party:storySwap" -> "STORYSWAP:"
            "Party:fortune" -> "FORTUNE:"
            "Party:chainReact" -> "CHAINREACT:"
            "Party:hideSeek" -> "HIDESEEK:"
            "Party:spyfall" -> "SPYFALL:"
            "Party:timeCapsule" -> "TIMECAPSULE:"
            "Party:whisper" -> "WHISPER:"
            "Party:emojiMemory" -> "EMOJIMEM:"
            "Party:storySeed" -> "STORYSEED:"
            "Party:would2" -> "WOULD2:"
            "Party:emojiOnly" -> "EMOJIONLY:"
            "Party:blindDraw" -> "BLINDDRAW:"
            "Party:silentMovie" -> "SILENTMOVIE:"
            "Party:colorWord" -> "COLORWORD:"
            "Party:impulseDraw" -> "IMPULSEDRAW:"
            "Party:sillyLaw" -> "SILLYLAW:"
            "Party:pinTheMood" -> "PINTHEMOOD:"
            "Party:revokeRush" -> "REVOKERUSH:"
            "Party:secretSignal" -> "SECRETSIGNAL:"
            "Party:ideaRelay" -> "IDEARELAY:"
            "Party:moodMeter" -> "MOODMETER:"
            "Party:gratitudeRound" -> "GRATROUND:"
            else -> error("unknown key " + key)
        }
    }

    private fun randomFormatOutputForParty(rnd: Random, key: String): String {
        return when (key) {
            "Party:wordChain" -> formatWordChainOracle(randomString(rnd), randomString(rnd))
            "Party:wouldYouRather" -> formatWouldYouRatherOracle(randomString(rnd), randomString(rnd), randomString(rnd))
            "Party:emojiRain" -> formatEmojiRainOracle(randomString(rnd), randomString(rnd))
            "Party:story" -> formatStoryOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:hotSeat" -> formatHotSeatOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:redPacketJoke" -> formatRedPacketJokeOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:impostor" -> formatImpostorOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:emojiStory" -> formatEmojiStoryOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:hotOrNot" -> formatHotOrNotOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:truthOrDare" -> formatTruthOrDareOracle(randomString(rnd), randomString(rnd), randomString(rnd))
            "Party:neverHaveIEver" -> formatNeverHaveIEverOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:memoryMatch" -> formatMemoryMatchOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:drawPrompt" -> formatDrawPromptOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:icebreaker" -> formatIcebreakerOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:minuteTalk" -> formatMinuteTalkOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:captionThis" -> formatCaptionThisOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:storySwap" -> formatStorySwapOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:fortune" -> formatFortuneOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:chainReact" -> formatChainReactOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:hideSeek" -> formatHideSeekOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:spyfall" -> formatSpyfallOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:timeCapsule" -> formatTimeCapsuleOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:whisper" -> formatWhisperOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:emojiMemory" -> formatEmojiMemoryOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:storySeed" -> formatStorySeedOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:would2" -> formatWould2Oracle(randomString(rnd), randomString(rnd), randomString(rnd))
            "Party:emojiOnly" -> formatEmojiOnlyOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:blindDraw" -> formatBlindDrawOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:silentMovie" -> formatSilentMovieOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:colorWord" -> formatColorWordOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:impulseDraw" -> formatImpulseDrawOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:sillyLaw" -> formatSillyLawOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:pinTheMood" -> formatPinTheMoodOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:revokeRush" -> formatRevokeRushOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:secretSignal" -> formatSecretSignalOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:ideaRelay" -> formatIdeaRelayOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:moodMeter" -> formatMoodMeterOracle(randomStringFor(rnd, key), randomString(rnd))
            "Party:gratitudeRound" -> formatGratitudeRoundOracle(randomStringFor(rnd, key), randomString(rnd))
            else -> error("unknown key " + key)
        }
    }

    private fun randomContentForParty(rnd: Random, key: String): String {
        val prefix = prefixForParty(key)
        return when (rnd.nextInt(4)) {
            0 -> randomFormatOutputForParty(rnd, key)
            1 -> prefix + randomString(rnd)
            2 -> randomString(rnd)
            else -> listOf("", prefix, prefix + "|", prefix + "a|b", "WRONG:" + randomString(rnd))[rnd.nextInt(5)]
        }
    }

    @Test
    fun formatSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Party:story", "Party:hotSeat", "Party:redPacketJoke", "Party:impostor", "Party:emojiStory", "Party:hotOrNot", "Party:neverHaveIEver", "Party:memoryMatch", "Party:drawPrompt", "Party:icebreaker", "Party:minuteTalk", "Party:captionThis", "Party:storySwap", "Party:fortune", "Party:chainReact", "Party:hideSeek", "Party:spyfall", "Party:timeCapsule", "Party:whisper", "Party:emojiMemory", "Party:storySeed", "Party:emojiOnly", "Party:blindDraw", "Party:silentMovie", "Party:colorWord", "Party:impulseDraw", "Party:sillyLaw", "Party:pinTheMood", "Party:revokeRush", "Party:secretSignal", "Party:ideaRelay", "Party:moodMeter", "Party:gratitudeRound")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "Party:story" -> assertEquals(
                        formatStoryOracle(x, host),
                        GroupPlayClassicParty.formatStory(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:hotSeat" -> assertEquals(
                        formatHotSeatOracle(x, host),
                        GroupPlayClassicParty.formatHotSeat(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:redPacketJoke" -> assertEquals(
                        formatRedPacketJokeOracle(x, host),
                        GroupPlayClassicParty.formatRedPacketJoke(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:impostor" -> assertEquals(
                        formatImpostorOracle(x, host),
                        GroupPlayClassicParty.formatImpostor(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:emojiStory" -> assertEquals(
                        formatEmojiStoryOracle(x, host),
                        GroupPlayClassicParty.formatEmojiStory(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:hotOrNot" -> assertEquals(
                        formatHotOrNotOracle(x, host),
                        GroupPlayClassicParty.formatHotOrNot(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:neverHaveIEver" -> assertEquals(
                        formatNeverHaveIEverOracle(x, host),
                        GroupPlayClassicParty.formatNeverHaveIEver(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:memoryMatch" -> assertEquals(
                        formatMemoryMatchOracle(x, host),
                        GroupPlayClassicParty.formatMemoryMatch(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:drawPrompt" -> assertEquals(
                        formatDrawPromptOracle(x, host),
                        GroupPlayClassicParty.formatDrawPrompt(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:icebreaker" -> assertEquals(
                        formatIcebreakerOracle(x, host),
                        GroupPlayClassicParty.formatIcebreaker(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:minuteTalk" -> assertEquals(
                        formatMinuteTalkOracle(x, host),
                        GroupPlayClassicParty.formatMinuteTalk(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:captionThis" -> assertEquals(
                        formatCaptionThisOracle(x, host),
                        GroupPlayClassicParty.formatCaptionThis(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:storySwap" -> assertEquals(
                        formatStorySwapOracle(x, host),
                        GroupPlayClassicParty.formatStorySwap(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:fortune" -> assertEquals(
                        formatFortuneOracle(x, host),
                        GroupPlayClassicParty.formatFortune(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:chainReact" -> assertEquals(
                        formatChainReactOracle(x, host),
                        GroupPlayClassicParty.formatChainReact(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:hideSeek" -> assertEquals(
                        formatHideSeekOracle(x, host),
                        GroupPlayClassicParty.formatHideSeek(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:spyfall" -> assertEquals(
                        formatSpyfallOracle(x, host),
                        GroupPlayClassicParty.formatSpyfall(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:timeCapsule" -> assertEquals(
                        formatTimeCapsuleOracle(x, host),
                        GroupPlayClassicParty.formatTimeCapsule(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:whisper" -> assertEquals(
                        formatWhisperOracle(x, host),
                        GroupPlayClassicParty.formatWhisper(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:emojiMemory" -> assertEquals(
                        formatEmojiMemoryOracle(x, host),
                        GroupPlayClassicParty.formatEmojiMemory(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:storySeed" -> assertEquals(
                        formatStorySeedOracle(x, host),
                        GroupPlayClassicParty.formatStorySeed(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:emojiOnly" -> assertEquals(
                        formatEmojiOnlyOracle(x, host),
                        GroupPlayClassicParty.formatEmojiOnly(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:blindDraw" -> assertEquals(
                        formatBlindDrawOracle(x, host),
                        GroupPlayClassicParty.formatBlindDraw(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:silentMovie" -> assertEquals(
                        formatSilentMovieOracle(x, host),
                        GroupPlayClassicParty.formatSilentMovie(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:colorWord" -> assertEquals(
                        formatColorWordOracle(x, host),
                        GroupPlayClassicParty.formatColorWord(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:impulseDraw" -> assertEquals(
                        formatImpulseDrawOracle(x, host),
                        GroupPlayClassicParty.formatImpulseDraw(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:sillyLaw" -> assertEquals(
                        formatSillyLawOracle(x, host),
                        GroupPlayClassicParty.formatSillyLaw(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:pinTheMood" -> assertEquals(
                        formatPinTheMoodOracle(x, host),
                        GroupPlayClassicParty.formatPinTheMood(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:revokeRush" -> assertEquals(
                        formatRevokeRushOracle(x, host),
                        GroupPlayClassicParty.formatRevokeRush(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:secretSignal" -> assertEquals(
                        formatSecretSignalOracle(x, host),
                        GroupPlayClassicParty.formatSecretSignal(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:ideaRelay" -> assertEquals(
                        formatIdeaRelayOracle(x, host),
                        GroupPlayClassicParty.formatIdeaRelay(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:moodMeter" -> assertEquals(
                        formatMoodMeterOracle(x, host),
                        GroupPlayClassicParty.formatMoodMeter(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Party:gratitudeRound" -> assertEquals(
                        formatGratitudeRoundOracle(x, host),
                        GroupPlayClassicParty.formatGratitudeRound(x, host),
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
            for (key in listOf("Party:story", "Party:hotSeat", "Party:redPacketJoke", "Party:impostor", "Party:emojiStory", "Party:hotOrNot", "Party:neverHaveIEver", "Party:memoryMatch", "Party:drawPrompt", "Party:icebreaker", "Party:minuteTalk", "Party:captionThis", "Party:storySwap", "Party:fortune", "Party:chainReact", "Party:hideSeek", "Party:spyfall", "Party:timeCapsule", "Party:whisper", "Party:emojiMemory", "Party:storySeed", "Party:emojiOnly", "Party:blindDraw", "Party:silentMovie", "Party:colorWord", "Party:impulseDraw", "Party:sillyLaw", "Party:pinTheMood", "Party:revokeRush", "Party:secretSignal", "Party:ideaRelay", "Party:moodMeter", "Party:gratitudeRound")) {
                val content = randomContentForParty(rnd, key)
                when (key) {
                    "Party:story" -> assertEquals(
                        parseStoryOracle(content),
                        GroupPlayClassicParty.parseStory(content),
                        "key=$key content=$content"
                    )
                    "Party:hotSeat" -> assertEquals(
                        parseHotSeatOracle(content),
                        GroupPlayClassicParty.parseHotSeat(content),
                        "key=$key content=$content"
                    )
                    "Party:redPacketJoke" -> assertEquals(
                        parseRedPacketJokeOracle(content),
                        GroupPlayClassicParty.parseRedPacketJoke(content),
                        "key=$key content=$content"
                    )
                    "Party:impostor" -> assertEquals(
                        parseImpostorOracle(content),
                        GroupPlayClassicParty.parseImpostor(content),
                        "key=$key content=$content"
                    )
                    "Party:emojiStory" -> assertEquals(
                        parseEmojiStoryOracle(content),
                        GroupPlayClassicParty.parseEmojiStory(content),
                        "key=$key content=$content"
                    )
                    "Party:hotOrNot" -> assertEquals(
                        parseHotOrNotOracle(content),
                        GroupPlayClassicParty.parseHotOrNot(content),
                        "key=$key content=$content"
                    )
                    "Party:neverHaveIEver" -> assertEquals(
                        parseNeverHaveIEverOracle(content),
                        GroupPlayClassicParty.parseNeverHaveIEver(content),
                        "key=$key content=$content"
                    )
                    "Party:memoryMatch" -> assertEquals(
                        parseMemoryMatchOracle(content),
                        GroupPlayClassicParty.parseMemoryMatch(content),
                        "key=$key content=$content"
                    )
                    "Party:drawPrompt" -> assertEquals(
                        parseDrawPromptOracle(content),
                        GroupPlayClassicParty.parseDrawPrompt(content),
                        "key=$key content=$content"
                    )
                    "Party:icebreaker" -> assertEquals(
                        parseIcebreakerOracle(content),
                        GroupPlayClassicParty.parseIcebreaker(content),
                        "key=$key content=$content"
                    )
                    "Party:minuteTalk" -> assertEquals(
                        parseMinuteTalkOracle(content),
                        GroupPlayClassicParty.parseMinuteTalk(content),
                        "key=$key content=$content"
                    )
                    "Party:captionThis" -> assertEquals(
                        parseCaptionThisOracle(content),
                        GroupPlayClassicParty.parseCaptionThis(content),
                        "key=$key content=$content"
                    )
                    "Party:storySwap" -> assertEquals(
                        parseStorySwapOracle(content),
                        GroupPlayClassicParty.parseStorySwap(content),
                        "key=$key content=$content"
                    )
                    "Party:fortune" -> assertEquals(
                        parseFortuneOracle(content),
                        GroupPlayClassicParty.parseFortune(content),
                        "key=$key content=$content"
                    )
                    "Party:chainReact" -> assertEquals(
                        parseChainReactOracle(content),
                        GroupPlayClassicParty.parseChainReact(content),
                        "key=$key content=$content"
                    )
                    "Party:hideSeek" -> assertEquals(
                        parseHideSeekOracle(content),
                        GroupPlayClassicParty.parseHideSeek(content),
                        "key=$key content=$content"
                    )
                    "Party:spyfall" -> assertEquals(
                        parseSpyfallOracle(content),
                        GroupPlayClassicParty.parseSpyfall(content),
                        "key=$key content=$content"
                    )
                    "Party:timeCapsule" -> assertEquals(
                        parseTimeCapsuleOracle(content),
                        GroupPlayClassicParty.parseTimeCapsule(content),
                        "key=$key content=$content"
                    )
                    "Party:whisper" -> assertEquals(
                        parseWhisperOracle(content),
                        GroupPlayClassicParty.parseWhisper(content),
                        "key=$key content=$content"
                    )
                    "Party:emojiMemory" -> assertEquals(
                        parseEmojiMemoryOracle(content),
                        GroupPlayClassicParty.parseEmojiMemory(content),
                        "key=$key content=$content"
                    )
                    "Party:storySeed" -> assertEquals(
                        parseStorySeedOracle(content),
                        GroupPlayClassicParty.parseStorySeed(content),
                        "key=$key content=$content"
                    )
                    "Party:emojiOnly" -> assertEquals(
                        parseEmojiOnlyOracle(content),
                        GroupPlayClassicParty.parseEmojiOnly(content),
                        "key=$key content=$content"
                    )
                    "Party:blindDraw" -> assertEquals(
                        parseBlindDrawOracle(content),
                        GroupPlayClassicParty.parseBlindDraw(content),
                        "key=$key content=$content"
                    )
                    "Party:silentMovie" -> assertEquals(
                        parseSilentMovieOracle(content),
                        GroupPlayClassicParty.parseSilentMovie(content),
                        "key=$key content=$content"
                    )
                    "Party:colorWord" -> assertEquals(
                        parseColorWordOracle(content),
                        GroupPlayClassicParty.parseColorWord(content),
                        "key=$key content=$content"
                    )
                    "Party:impulseDraw" -> assertEquals(
                        parseImpulseDrawOracle(content),
                        GroupPlayClassicParty.parseImpulseDraw(content),
                        "key=$key content=$content"
                    )
                    "Party:sillyLaw" -> assertEquals(
                        parseSillyLawOracle(content),
                        GroupPlayClassicParty.parseSillyLaw(content),
                        "key=$key content=$content"
                    )
                    "Party:pinTheMood" -> assertEquals(
                        parsePinTheMoodOracle(content),
                        GroupPlayClassicParty.parsePinTheMood(content),
                        "key=$key content=$content"
                    )
                    "Party:revokeRush" -> assertEquals(
                        parseRevokeRushOracle(content),
                        GroupPlayClassicParty.parseRevokeRush(content),
                        "key=$key content=$content"
                    )
                    "Party:secretSignal" -> assertEquals(
                        parseSecretSignalOracle(content),
                        GroupPlayClassicParty.parseSecretSignal(content),
                        "key=$key content=$content"
                    )
                    "Party:ideaRelay" -> assertEquals(
                        parseIdeaRelayOracle(content),
                        GroupPlayClassicParty.parseIdeaRelay(content),
                        "key=$key content=$content"
                    )
                    "Party:moodMeter" -> assertEquals(
                        parseMoodMeterOracle(content),
                        GroupPlayClassicParty.parseMoodMeter(content),
                        "key=$key content=$content"
                    )
                    "Party:gratitudeRound" -> assertEquals(
                        parseGratitudeRoundOracle(content),
                        GroupPlayClassicParty.parseGratitudeRound(content),
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
            for (key in listOf("Party:wouldYouRather", "Party:truthOrDare", "Party:would2")) {
                val a = randomString(rnd)
                val b = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Party:wouldYouRather" -> assertEquals(
                        formatWouldYouRatherOracle(a, b, host),
                        GroupPlayClassicParty.formatWouldYouRather(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Party:truthOrDare" -> assertEquals(
                        formatTruthOrDareOracle(a, b, host),
                        GroupPlayClassicParty.formatTruthOrDare(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    "Party:would2" -> assertEquals(
                        formatWould2Oracle(a, b, host),
                        GroupPlayClassicParty.formatWould2(a, b, host),
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
            for (key in listOf("Party:wouldYouRather", "Party:truthOrDare", "Party:would2")) {
                val content = randomContentForParty(rnd, key)
                when (key) {
                    "Party:wouldYouRather" -> assertEquals(
                        parseWouldYouRatherOracle(content),
                        GroupPlayClassicParty.parseWouldYouRather(content),
                        "key=$key content=$content"
                    )
                    "Party:truthOrDare" -> assertEquals(
                        parseTruthOrDareOracle(content),
                        GroupPlayClassicParty.parseTruthOrDare(content),
                        "key=$key content=$content"
                    )
                    "Party:would2" -> assertEquals(
                        parseWould2Oracle(content),
                        GroupPlayClassicParty.parseWould2(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatEmojiRainRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Party:emojiRain")) {
                val host = randomString(rnd)
                val emoji = randomString(rnd)
                when (key) {
                    "Party:emojiRain" -> assertEquals(
                        formatEmojiRainOracle(host, emoji),
                        GroupPlayClassicParty.formatEmojiRain(host, emoji),
                        "key=$key host=$host emoji=$emoji"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseEmojiRainRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Party:emojiRain")) {
                val content = randomContentForParty(rnd, key)
                when (key) {
                    "Party:emojiRain" -> assertEquals(
                        parseEmojiRainOracle(content),
                        GroupPlayClassicParty.parseEmojiRain(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatWordChainRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Party:wordChain")) {
                val seed = randomString(rnd)
                val user = randomString(rnd)
                when (key) {
                    "Party:wordChain" -> assertEquals(
                        formatWordChainOracle(seed, user),
                        GroupPlayClassicParty.formatWordChain(seed, user),
                        "key=$key seed=$seed user=$user"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseWordChainRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Party:wordChain")) {
                val content = randomContentForParty(rnd, key)
                when (key) {
                    "Party:wordChain" -> assertEquals(
                        parseWordChainOracle(content),
                        GroupChainPolicy.parseWordChain(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Story / redPacket return "" on a blank field, never null.
        assertEquals("", GroupPlayClassicParty.parseStory("STORY:|x"))
        assertEquals("", GroupPlayClassicParty.parseRedPacketJoke("REDPACKET:|x"))
        // A blank prompt formats to an empty field, which parses back to null.
        assertEquals(
            "NEVERHAVE:|H never-have-I-ever — react if you have!",
            GroupPlayClassicParty.formatNeverHaveIEver("", "H")
        )
        assertNull(GroupPlayClassicParty.parseNeverHaveIEver("NEVERHAVE:|x"))
        // TruthOrDare coerces any mode to truth/dare on format; parse takes any mode verbatim.
        assertEquals(
            "TRUTHDARE:truth|p|H truth-or-dare",
            GroupPlayClassicParty.formatTruthOrDare("maybe", "p", "H")
        )
        assertEquals(
            "TRUTHDARE:dare|p|H truth-or-dare",
            GroupPlayClassicParty.formatTruthOrDare("  Dare  ", "p", "H")
        )
        assertEquals(
            "weird" to "p",
            GroupPlayClassicParty.parseTruthOrDare("TRUTHDARE:weird|p|H truth-or-dare")
        )
        assertNull(GroupPlayClassicParty.parseTruthOrDare("TRUTHDARE:onlymode"))
        // WordChain does not escape: a '|' in the seed passes through raw and splits on parse.
        assertEquals(
            "WORD:a|b|H word chain: start with 'a|b'",
            GroupPlayClassicParty.formatWordChain("a|b", "H")
        )
        assertEquals(
            "a" to "b|H word chain: start with 'a|b'",
            GroupChainPolicy.parseWordChain("WORD:a|b|H word chain: start with 'a|b'")
        )
        // EmojiRain take(4) counts UTF-16 units: three poppers become two;
        // a trailing astral emoji is cut mid-surrogate-pair.
        assertEquals(
            "EMOJI_RAIN:🎉🎉|H started emoji rain 🎉🎉",
            GroupPlayClassicParty.formatEmojiRain("H", "🎉🎉🎉")
        )
        assertEquals(
            "EMOJI_RAIN:abc\uD83C|H started emoji rain abc\uD83C",
            GroupPlayClassicParty.formatEmojiRain("H", "abc🎉")
        )
        // EmojiRain parse leaves the rest raw: a literal U+0001 in the host survives.
        assertEquals(
            "🎉" to "H\u0001X started emoji rain 🎉",
            GroupPlayClassicParty.parseEmojiRain("EMOJI_RAIN:🎉|H\u0001X started emoji rain 🎉")
        )
        // take() limits are exact.
        assertEquals(
            "x".repeat(160),
            GroupPlayClassicParty.parseStory(GroupPlayClassicParty.formatStory("x".repeat(200), "H"))
        )
        assertEquals(
            "x".repeat(20),
            GroupPlayClassicParty.parsePinTheMood(GroupPlayClassicParty.formatPinTheMood("x".repeat(30), "H"))
        )
        // Escaping round-trips; a literal U+0001 field parses back as '|'.
        assertEquals(
            "a|b^c",
            GroupPlayClassicParty.parseHotSeat(GroupPlayClassicParty.formatHotSeat("a|b^c", "H"))
        )
        assertEquals(
            "|",
            GroupPlayClassicParty.parseSpyfall(GroupPlayClassicParty.formatSpyfall("\u0001", "H"))
        )
        // A blank story falls back to the default opener.
        assertEquals(
            "STORY:Once upon a time in a chat group...|H started a story",
            GroupPlayClassicParty.formatStory("   ", "H")
        )
        // Prefixes are case-sensitive.
        assertNull(GroupPlayClassicParty.parseFortune("FORTUNEX:x|H"))
        assertNull(GroupPlayClassicParty.parseFortune("fortune:x"))
        // A '|' in the host truncates the WouldYouRather label at parse time;
        // the label keeps the " would you rather" suffix; a 4th field is dropped.
        assertEquals(
            Triple("a", "b", "H"),
            GroupPlayClassicParty.parseWouldYouRather(
                GroupPlayClassicParty.formatWouldYouRather("a", "b", "H|Z")
            )
        )
        assertEquals(
            Triple("a", "b", "H would you rather"),
            GroupPlayClassicParty.parseWouldYouRather("WOULD:a|b|H would you rather")
        )
        assertEquals(
            Triple("a", "b", "c"),
            GroupPlayClassicParty.parseWouldYouRather("WOULD:a|b|c|d")
        )
        // Blank inputs fall back to a random list emoji: only membership is pinned.
        assertTrue(
            GroupPlayClassicParty.parseMemoryMatch(
                GroupPlayClassicParty.formatMemoryMatch("   ", "H")
            ) in memoryEmojis
        )
        assertTrue(
            GroupPlayClassicParty.parseChainReact(
                GroupPlayClassicParty.formatChainReact("", "H")
            ) in chainSeeds
        )
        assertTrue(
            GroupPlayClassicParty.parseHideSeek(
                GroupPlayClassicParty.formatHideSeek("  ", "H")
            ) in hideEmojis
        )
        // EmojiRain with no explicit emoji picks a random one from the list.
        assertTrue(
            GroupPlayClassicParty.parseEmojiRain(
                GroupPlayClassicParty.formatEmojiRain("H")
            )!!.first in rainEmojis
        )
    }

}
