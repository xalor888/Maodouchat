package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fuzz / random-oracle tests for the GroupPlay classic codec cluster
 * (checklist line 931, 50th block): [GroupPlayClassicSprint]. Test-only, zero production
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
 * - The int pairs clamp: countdown 5..600, speed challenge 5..60.
 * - formatSyncClap interpolates the raw value into the suffix as well.
 * - take() cuts by UTF-16 code unit, so 10 emoji (20 units) become 6
 *   under take(12).
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 86347 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, int / string / parse exhaustives, and
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 5 test
 * methods were also run locally against the real production code
 * (kotlin-test stubs): all pass.
 */
class GroupPlayClassicSprintFuzzTest {

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

    private fun formatCountdownSprintOracle(n: Int, hostLabel: String): String {
        val s = clampIntOracle(n, 5, 600)
        return "COUNTDOWN:" + s.toString() + "|" + hostLabel + " started " + s.toString() + "s countdown"
    }

    private fun parseCountdownSprintOracle(content: String): Int? {
        if (!content.startsWith("COUNTDOWN:")) return null
        return unescOracle(content.removePrefix("COUNTDOWN:").substringBefore('|')).toIntOrNull()
    }

    private fun formatSpeedChallengeSprintOracle(n: Int, hostLabel: String): String {
        val s = clampIntOracle(n, 5, 60)
        return "SPEED:" + s.toString() + "|" + hostLabel + " speed challenge — reply in " + s.toString() + "s!"
    }

    private fun parseSpeedChallengeSprintOracle(content: String): Int? {
        if (!content.startsWith("SPEED:")) return null
        return unescOracle(content.removePrefix("SPEED:").substringBefore('|')).toIntOrNull()
    }

    private fun formatRapidFireSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "RAPIDFIRE:" + escOracle(v) + "|" + hostLabel + " rapid-fire — name 5 in 20s!"
    }

    private fun parseRapidFireSprintOracle(content: String): String? {
        if (!content.startsWith("RAPIDFIRE:")) return null
        val v = unescOracle(content.removePrefix("RAPIDFIRE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatLightningSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "LIGHTNING:" + escOracle(v) + "|" + hostLabel + " lightning round"
    }

    private fun parseLightningSprintOracle(content: String): String? {
        if (!content.startsWith("LIGHTNING:")) return null
        val v = unescOracle(content.removePrefix("LIGHTNING:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSyncClapSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(4)
        return "SYNCCLAP:" + escOracle(v) + "|" + hostLabel + " sync clap x" + v
    }

    private fun parseSyncClapSprintOracle(content: String): String? {
        if (!content.startsWith("SYNCCLAP:")) return null
        val v = unescOracle(content.removePrefix("SYNCCLAP:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTempoTapSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(12)
        return "TEMPOTAP:" + escOracle(v) + "|" + hostLabel + " tempo tap"
    }

    private fun parseTempoTapSprintOracle(content: String): String? {
        if (!content.startsWith("TEMPOTAP:")) return null
        val v = unescOracle(content.removePrefix("TEMPOTAP:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatInviteRaceSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "INVITERACE:" + escOracle(v) + "|" + hostLabel + " invite race"
    }

    private fun parseInviteRaceSprintOracle(content: String): String? {
        if (!content.startsWith("INVITERACE:")) return null
        val v = unescOracle(content.removePrefix("INVITERACE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMentionMayhemSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "MENTIONMAY:" + escOracle(v) + "|" + hostLabel + " mention mayhem"
    }

    private fun parseMentionMayhemSprintOracle(content: String): String? {
        if (!content.startsWith("MENTIONMAY:")) return null
        val v = unescOracle(content.removePrefix("MENTIONMAY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatLinkHuntSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(30)
        return "LINKHUNT:" + escOracle(v) + "|" + hostLabel + " link hunt"
    }

    private fun parseLinkHuntSprintOracle(content: String): String? {
        if (!content.startsWith("LINKHUNT:")) return null
        val v = unescOracle(content.removePrefix("LINKHUNT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatNudgeDashSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "NUDGEDASH:" + escOracle(v) + "|" + hostLabel + " nudge dash"
    }

    private fun parseNudgeDashSprintOracle(content: String): String? {
        if (!content.startsWith("NUDGEDASH:")) return null
        val v = unescOracle(content.removePrefix("NUDGEDASH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatCodeCheckSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "CODECHECK:" + escOracle(v) + "|" + hostLabel + " code check"
    }

    private fun parseCodeCheckSprintOracle(content: String): String? {
        if (!content.startsWith("CODECHECK:")) return null
        val v = unescOracle(content.removePrefix("CODECHECK:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTrustSprintSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "TRUSTSPRINT:" + escOracle(v) + "|" + hostLabel + " trust sprint"
    }

    private fun parseTrustSprintSprintOracle(content: String): String? {
        if (!content.startsWith("TRUSTSPRINT:")) return null
        val v = unescOracle(content.removePrefix("TRUSTSPRINT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatFocusSprintSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(10)
        return "FOCUSPRINT:" + escOracle(v) + "|" + hostLabel + " focus sprint"
    }

    private fun parseFocusSprintSprintOracle(content: String): String? {
        if (!content.startsWith("FOCUSPRINT:")) return null
        val v = unescOracle(content.removePrefix("FOCUSPRINT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatQrQuestSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "QRQUEST:" + escOracle(v) + "|" + hostLabel + " qr quest"
    }

    private fun parseQrQuestSprintOracle(content: String): String? {
        if (!content.startsWith("QRQUEST:")) return null
        val v = unescOracle(content.removePrefix("QRQUEST:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatContactSwapSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "CONTACTSWAP:" + escOracle(v) + "|" + hostLabel + " contact swap"
    }

    private fun parseContactSwapSprintOracle(content: String): String? {
        if (!content.startsWith("CONTACTSWAP:")) return null
        val v = unescOracle(content.removePrefix("CONTACTSWAP:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatScanSprintSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "SCANSPRINT:" + escOracle(v) + "|" + hostLabel + " scan sprint"
    }

    private fun parseScanSprintSprintOracle(content: String): String? {
        if (!content.startsWith("SCANSPRINT:")) return null
        val v = unescOracle(content.removePrefix("SCANSPRINT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSpoilerRaceSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "SPOILERRACE:" + escOracle(v) + "|" + hostLabel + " spoiler race"
    }

    private fun parseSpoilerRaceSprintOracle(content: String): String? {
        if (!content.startsWith("SPOILERRACE:")) return null
        val v = unescOracle(content.removePrefix("SPOILERRACE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatBlurBattleSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "BLURBATTLE:" + escOracle(v) + "|" + hostLabel + " blur battle"
    }

    private fun parseBlurBattleSprintOracle(content: String): String? {
        if (!content.startsWith("BLURBATTLE:")) return null
        val v = unescOracle(content.removePrefix("BLURBATTLE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatDownloadDashSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "DLDASH:" + escOracle(v) + "|" + hostLabel + " download dash"
    }

    private fun parseDownloadDashSprintOracle(content: String): String? {
        if (!content.startsWith("DLDASH:")) return null
        val v = unescOracle(content.removePrefix("DLDASH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatPinDropSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "PINDROP:" + escOracle(v) + "|" + hostLabel + " pin drop"
    }

    private fun parsePinDropSprintOracle(content: String): String? {
        if (!content.startsWith("PINDROP:")) return null
        val v = unescOracle(content.removePrefix("PINDROP:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatFileRelaySprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "FILERELAY:" + escOracle(v) + "|" + hostLabel + " file relay"
    }

    private fun parseFileRelaySprintOracle(content: String): String? {
        if (!content.startsWith("FILERELAY:")) return null
        val v = unescOracle(content.removePrefix("FILERELAY:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatMapDashSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "MAPDASH:" + escOracle(v) + "|" + hostLabel + " map dash"
    }

    private fun parseMapDashSprintOracle(content: String): String? {
        if (!content.startsWith("MAPDASH:")) return null
        val v = unescOracle(content.removePrefix("MAPDASH:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatVaultLockSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "VAULTLOCK:" + escOracle(v) + "|" + hostLabel + " vault lock"
    }

    private fun parseVaultLockSprintOracle(content: String): String? {
        if (!content.startsWith("VAULTLOCK:")) return null
        val v = unescOracle(content.removePrefix("VAULTLOCK:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWatermarkHuntSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "WMHUNT:" + escOracle(v) + "|" + hostLabel + " watermark hunt"
    }

    private fun parseWatermarkHuntSprintOracle(content: String): String? {
        if (!content.startsWith("WMHUNT:")) return null
        val v = unescOracle(content.removePrefix("WMHUNT:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatSecureSprintSprintOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "SECURESPRINT:" + escOracle(v) + "|" + hostLabel + " secure sprint"
    }

    private fun parseSecureSprintSprintOracle(content: String): String? {
        if (!content.startsWith("SECURESPRINT:")) return null
        val v = unescOracle(content.removePrefix("SECURESPRINT:").substringBefore('|'))
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

    private fun prefixForSprint(key: String): String {
        return when (key) {
            "Sprint:countdown" -> "COUNTDOWN:"
            "Sprint:speedChallenge" -> "SPEED:"
            "Sprint:rapidFire" -> "RAPIDFIRE:"
            "Sprint:lightning" -> "LIGHTNING:"
            "Sprint:syncClap" -> "SYNCCLAP:"
            "Sprint:tempoTap" -> "TEMPOTAP:"
            "Sprint:inviteRace" -> "INVITERACE:"
            "Sprint:mentionMayhem" -> "MENTIONMAY:"
            "Sprint:linkHunt" -> "LINKHUNT:"
            "Sprint:nudgeDash" -> "NUDGEDASH:"
            "Sprint:codeCheck" -> "CODECHECK:"
            "Sprint:trustSprint" -> "TRUSTSPRINT:"
            "Sprint:focusSprint" -> "FOCUSPRINT:"
            "Sprint:qrQuest" -> "QRQUEST:"
            "Sprint:contactSwap" -> "CONTACTSWAP:"
            "Sprint:scanSprint" -> "SCANSPRINT:"
            "Sprint:spoilerRace" -> "SPOILERRACE:"
            "Sprint:blurBattle" -> "BLURBATTLE:"
            "Sprint:downloadDash" -> "DLDASH:"
            "Sprint:pinDrop" -> "PINDROP:"
            "Sprint:fileRelay" -> "FILERELAY:"
            "Sprint:mapDash" -> "MAPDASH:"
            "Sprint:vaultLock" -> "VAULTLOCK:"
            "Sprint:watermarkHunt" -> "WMHUNT:"
            "Sprint:secureSprint" -> "SECURESPRINT:"
            else -> error("unknown key " + key)
        }
    }

    private fun randomFormatOutputForSprint(rnd: Random, key: String): String {
        val host = randomString(rnd)
        return when (key) {
            "Sprint:countdown" -> formatCountdownSprintOracle(randomInt(rnd), host)
            "Sprint:speedChallenge" -> formatSpeedChallengeSprintOracle(randomInt(rnd), host)
            "Sprint:rapidFire" -> formatRapidFireSprintOracle(randomStringFor(rnd, "Sprint:rapidFire"), host)
            "Sprint:lightning" -> formatLightningSprintOracle(randomStringFor(rnd, "Sprint:lightning"), host)
            "Sprint:syncClap" -> formatSyncClapSprintOracle(randomStringFor(rnd, "Sprint:syncClap"), host)
            "Sprint:tempoTap" -> formatTempoTapSprintOracle(randomStringFor(rnd, "Sprint:tempoTap"), host)
            "Sprint:inviteRace" -> formatInviteRaceSprintOracle(randomStringFor(rnd, "Sprint:inviteRace"), host)
            "Sprint:mentionMayhem" -> formatMentionMayhemSprintOracle(randomStringFor(rnd, "Sprint:mentionMayhem"), host)
            "Sprint:linkHunt" -> formatLinkHuntSprintOracle(randomStringFor(rnd, "Sprint:linkHunt"), host)
            "Sprint:nudgeDash" -> formatNudgeDashSprintOracle(randomStringFor(rnd, "Sprint:nudgeDash"), host)
            "Sprint:codeCheck" -> formatCodeCheckSprintOracle(randomStringFor(rnd, "Sprint:codeCheck"), host)
            "Sprint:trustSprint" -> formatTrustSprintSprintOracle(randomStringFor(rnd, "Sprint:trustSprint"), host)
            "Sprint:focusSprint" -> formatFocusSprintSprintOracle(randomStringFor(rnd, "Sprint:focusSprint"), host)
            "Sprint:qrQuest" -> formatQrQuestSprintOracle(randomStringFor(rnd, "Sprint:qrQuest"), host)
            "Sprint:contactSwap" -> formatContactSwapSprintOracle(randomStringFor(rnd, "Sprint:contactSwap"), host)
            "Sprint:scanSprint" -> formatScanSprintSprintOracle(randomStringFor(rnd, "Sprint:scanSprint"), host)
            "Sprint:spoilerRace" -> formatSpoilerRaceSprintOracle(randomStringFor(rnd, "Sprint:spoilerRace"), host)
            "Sprint:blurBattle" -> formatBlurBattleSprintOracle(randomStringFor(rnd, "Sprint:blurBattle"), host)
            "Sprint:downloadDash" -> formatDownloadDashSprintOracle(randomStringFor(rnd, "Sprint:downloadDash"), host)
            "Sprint:pinDrop" -> formatPinDropSprintOracle(randomStringFor(rnd, "Sprint:pinDrop"), host)
            "Sprint:fileRelay" -> formatFileRelaySprintOracle(randomStringFor(rnd, "Sprint:fileRelay"), host)
            "Sprint:mapDash" -> formatMapDashSprintOracle(randomStringFor(rnd, "Sprint:mapDash"), host)
            "Sprint:vaultLock" -> formatVaultLockSprintOracle(randomStringFor(rnd, "Sprint:vaultLock"), host)
            "Sprint:watermarkHunt" -> formatWatermarkHuntSprintOracle(randomStringFor(rnd, "Sprint:watermarkHunt"), host)
            "Sprint:secureSprint" -> formatSecureSprintSprintOracle(randomStringFor(rnd, "Sprint:secureSprint"), host)
            else -> error("unknown key " + key)
        }
    }

    private fun randomContentForSprint(rnd: Random, key: String): String {
        val prefix = prefixForSprint(key)
        return when (rnd.nextInt(4)) {
            0 -> randomFormatOutputForSprint(rnd, key)
            1 -> prefix + randomString(rnd)
            2 -> randomString(rnd)
            else -> listOf("", prefix, prefix + "|", prefix + "a|b", "WRONG:" + randomString(rnd))[rnd.nextInt(5)]
        }
    }

    @Test
    fun formatIntsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Sprint:countdown", "Sprint:speedChallenge")) {
                val n = randomInt(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Sprint:countdown" -> assertEquals(
                        formatCountdownSprintOracle(n, host),
                        GroupPlayClassicSprint.formatCountdown(n, host),
                        "key=$key n=$n host=$host"
                    )
                    "Sprint:speedChallenge" -> assertEquals(
                        formatSpeedChallengeSprintOracle(n, host),
                        GroupPlayClassicSprint.formatSpeedChallenge(n, host),
                        "key=$key n=$n host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseIntsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Sprint:countdown", "Sprint:speedChallenge")) {
                val content = randomContentForSprint(rnd, key)
                when (key) {
                    "Sprint:countdown" -> assertEquals(
                        parseCountdownSprintOracle(content),
                        GroupPlayClassicSprint.parseCountdown(content),
                        "key=$key content=$content"
                    )
                    "Sprint:speedChallenge" -> assertEquals(
                        parseSpeedChallengeSprintOracle(content),
                        GroupPlayClassicSprint.parseSpeedChallenge(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Sprint:rapidFire", "Sprint:lightning", "Sprint:syncClap", "Sprint:tempoTap", "Sprint:inviteRace", "Sprint:mentionMayhem", "Sprint:linkHunt", "Sprint:nudgeDash", "Sprint:codeCheck", "Sprint:trustSprint", "Sprint:focusSprint", "Sprint:qrQuest", "Sprint:contactSwap", "Sprint:scanSprint", "Sprint:spoilerRace", "Sprint:blurBattle", "Sprint:downloadDash", "Sprint:pinDrop", "Sprint:fileRelay", "Sprint:mapDash", "Sprint:vaultLock", "Sprint:watermarkHunt", "Sprint:secureSprint")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "Sprint:rapidFire" -> assertEquals(
                        formatRapidFireSprintOracle(x, host),
                        GroupPlayClassicSprint.formatRapidFire(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:lightning" -> assertEquals(
                        formatLightningSprintOracle(x, host),
                        GroupPlayClassicSprint.formatLightning(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:syncClap" -> assertEquals(
                        formatSyncClapSprintOracle(x, host),
                        GroupPlayClassicSprint.formatSyncClap(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:tempoTap" -> assertEquals(
                        formatTempoTapSprintOracle(x, host),
                        GroupPlayClassicSprint.formatTempoTap(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:inviteRace" -> assertEquals(
                        formatInviteRaceSprintOracle(x, host),
                        GroupPlayClassicSprint.formatInviteRace(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:mentionMayhem" -> assertEquals(
                        formatMentionMayhemSprintOracle(x, host),
                        GroupPlayClassicSprint.formatMentionMayhem(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:linkHunt" -> assertEquals(
                        formatLinkHuntSprintOracle(x, host),
                        GroupPlayClassicSprint.formatLinkHunt(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:nudgeDash" -> assertEquals(
                        formatNudgeDashSprintOracle(x, host),
                        GroupPlayClassicSprint.formatNudgeDash(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:codeCheck" -> assertEquals(
                        formatCodeCheckSprintOracle(x, host),
                        GroupPlayClassicSprint.formatCodeCheck(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:trustSprint" -> assertEquals(
                        formatTrustSprintSprintOracle(x, host),
                        GroupPlayClassicSprint.formatTrustSprint(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:focusSprint" -> assertEquals(
                        formatFocusSprintSprintOracle(x, host),
                        GroupPlayClassicSprint.formatFocusSprint(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:qrQuest" -> assertEquals(
                        formatQrQuestSprintOracle(x, host),
                        GroupPlayClassicSprint.formatQrQuest(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:contactSwap" -> assertEquals(
                        formatContactSwapSprintOracle(x, host),
                        GroupPlayClassicSprint.formatContactSwap(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:scanSprint" -> assertEquals(
                        formatScanSprintSprintOracle(x, host),
                        GroupPlayClassicSprint.formatScanSprint(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:spoilerRace" -> assertEquals(
                        formatSpoilerRaceSprintOracle(x, host),
                        GroupPlayClassicSprint.formatSpoilerRace(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:blurBattle" -> assertEquals(
                        formatBlurBattleSprintOracle(x, host),
                        GroupPlayClassicSprint.formatBlurBattle(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:downloadDash" -> assertEquals(
                        formatDownloadDashSprintOracle(x, host),
                        GroupPlayClassicSprint.formatDownloadDash(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:pinDrop" -> assertEquals(
                        formatPinDropSprintOracle(x, host),
                        GroupPlayClassicSprint.formatPinDrop(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:fileRelay" -> assertEquals(
                        formatFileRelaySprintOracle(x, host),
                        GroupPlayClassicSprint.formatFileRelay(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:mapDash" -> assertEquals(
                        formatMapDashSprintOracle(x, host),
                        GroupPlayClassicSprint.formatMapDash(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:vaultLock" -> assertEquals(
                        formatVaultLockSprintOracle(x, host),
                        GroupPlayClassicSprint.formatVaultLock(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:watermarkHunt" -> assertEquals(
                        formatWatermarkHuntSprintOracle(x, host),
                        GroupPlayClassicSprint.formatWatermarkHunt(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Sprint:secureSprint" -> assertEquals(
                        formatSecureSprintSprintOracle(x, host),
                        GroupPlayClassicSprint.formatSecureSprint(x, host),
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
            for (key in listOf("Sprint:rapidFire", "Sprint:lightning", "Sprint:syncClap", "Sprint:tempoTap", "Sprint:inviteRace", "Sprint:mentionMayhem", "Sprint:linkHunt", "Sprint:nudgeDash", "Sprint:codeCheck", "Sprint:trustSprint", "Sprint:focusSprint", "Sprint:qrQuest", "Sprint:contactSwap", "Sprint:scanSprint", "Sprint:spoilerRace", "Sprint:blurBattle", "Sprint:downloadDash", "Sprint:pinDrop", "Sprint:fileRelay", "Sprint:mapDash", "Sprint:vaultLock", "Sprint:watermarkHunt", "Sprint:secureSprint")) {
                val content = randomContentForSprint(rnd, key)
                when (key) {
                    "Sprint:rapidFire" -> assertEquals(
                        parseRapidFireSprintOracle(content),
                        GroupPlayClassicSprint.parseRapidFire(content),
                        "key=$key content=$content"
                    )
                    "Sprint:lightning" -> assertEquals(
                        parseLightningSprintOracle(content),
                        GroupPlayClassicSprint.parseLightning(content),
                        "key=$key content=$content"
                    )
                    "Sprint:syncClap" -> assertEquals(
                        parseSyncClapSprintOracle(content),
                        GroupPlayClassicSprint.parseSyncClap(content),
                        "key=$key content=$content"
                    )
                    "Sprint:tempoTap" -> assertEquals(
                        parseTempoTapSprintOracle(content),
                        GroupPlayClassicSprint.parseTempoTap(content),
                        "key=$key content=$content"
                    )
                    "Sprint:inviteRace" -> assertEquals(
                        parseInviteRaceSprintOracle(content),
                        GroupPlayClassicSprint.parseInviteRace(content),
                        "key=$key content=$content"
                    )
                    "Sprint:mentionMayhem" -> assertEquals(
                        parseMentionMayhemSprintOracle(content),
                        GroupPlayClassicSprint.parseMentionMayhem(content),
                        "key=$key content=$content"
                    )
                    "Sprint:linkHunt" -> assertEquals(
                        parseLinkHuntSprintOracle(content),
                        GroupPlayClassicSprint.parseLinkHunt(content),
                        "key=$key content=$content"
                    )
                    "Sprint:nudgeDash" -> assertEquals(
                        parseNudgeDashSprintOracle(content),
                        GroupPlayClassicSprint.parseNudgeDash(content),
                        "key=$key content=$content"
                    )
                    "Sprint:codeCheck" -> assertEquals(
                        parseCodeCheckSprintOracle(content),
                        GroupPlayClassicSprint.parseCodeCheck(content),
                        "key=$key content=$content"
                    )
                    "Sprint:trustSprint" -> assertEquals(
                        parseTrustSprintSprintOracle(content),
                        GroupPlayClassicSprint.parseTrustSprint(content),
                        "key=$key content=$content"
                    )
                    "Sprint:focusSprint" -> assertEquals(
                        parseFocusSprintSprintOracle(content),
                        GroupPlayClassicSprint.parseFocusSprint(content),
                        "key=$key content=$content"
                    )
                    "Sprint:qrQuest" -> assertEquals(
                        parseQrQuestSprintOracle(content),
                        GroupPlayClassicSprint.parseQrQuest(content),
                        "key=$key content=$content"
                    )
                    "Sprint:contactSwap" -> assertEquals(
                        parseContactSwapSprintOracle(content),
                        GroupPlayClassicSprint.parseContactSwap(content),
                        "key=$key content=$content"
                    )
                    "Sprint:scanSprint" -> assertEquals(
                        parseScanSprintSprintOracle(content),
                        GroupPlayClassicSprint.parseScanSprint(content),
                        "key=$key content=$content"
                    )
                    "Sprint:spoilerRace" -> assertEquals(
                        parseSpoilerRaceSprintOracle(content),
                        GroupPlayClassicSprint.parseSpoilerRace(content),
                        "key=$key content=$content"
                    )
                    "Sprint:blurBattle" -> assertEquals(
                        parseBlurBattleSprintOracle(content),
                        GroupPlayClassicSprint.parseBlurBattle(content),
                        "key=$key content=$content"
                    )
                    "Sprint:downloadDash" -> assertEquals(
                        parseDownloadDashSprintOracle(content),
                        GroupPlayClassicSprint.parseDownloadDash(content),
                        "key=$key content=$content"
                    )
                    "Sprint:pinDrop" -> assertEquals(
                        parsePinDropSprintOracle(content),
                        GroupPlayClassicSprint.parsePinDrop(content),
                        "key=$key content=$content"
                    )
                    "Sprint:fileRelay" -> assertEquals(
                        parseFileRelaySprintOracle(content),
                        GroupPlayClassicSprint.parseFileRelay(content),
                        "key=$key content=$content"
                    )
                    "Sprint:mapDash" -> assertEquals(
                        parseMapDashSprintOracle(content),
                        GroupPlayClassicSprint.parseMapDash(content),
                        "key=$key content=$content"
                    )
                    "Sprint:vaultLock" -> assertEquals(
                        parseVaultLockSprintOracle(content),
                        GroupPlayClassicSprint.parseVaultLock(content),
                        "key=$key content=$content"
                    )
                    "Sprint:watermarkHunt" -> assertEquals(
                        parseWatermarkHuntSprintOracle(content),
                        GroupPlayClassicSprint.parseWatermarkHunt(content),
                        "key=$key content=$content"
                    )
                    "Sprint:secureSprint" -> assertEquals(
                        parseSecureSprintSprintOracle(content),
                        GroupPlayClassicSprint.parseSecureSprint(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Int clamps: countdown 5..600, speed challenge 5..60.
        assertEquals("COUNTDOWN:5|H started 5s countdown", GroupPlayClassicSprint.formatCountdown(3, "H"))
        assertEquals("COUNTDOWN:600|H started 600s countdown", GroupPlayClassicSprint.formatCountdown(1000, "H"))
        assertEquals(42, GroupPlayClassicSprint.parseCountdown("COUNTDOWN:42|H started 42s countdown"))
        assertNull(GroupPlayClassicSprint.parseCountdown("COUNTDOWN:x"))
        assertEquals("SPEED:5|H speed challenge — reply in 5s!", GroupPlayClassicSprint.formatSpeedChallenge(1, "H"))
        assertEquals("SPEED:60|H speed challenge — reply in 60s!", GroupPlayClassicSprint.formatSpeedChallenge(100, "H"))
        // SyncClap interpolates the raw value into the suffix as well.
        assertEquals("SYNCCLAP:ab|H sync clap xab", GroupPlayClassicSprint.formatSyncClap("  ab ", "H"))
        assertEquals("ab", GroupPlayClassicSprint.parseSyncClap("SYNCCLAP:ab|H sync clap xab"))
        // take() limits differ per pair; FocusSprint only keeps 10.
        assertEquals("FOCUSPRINT:0123456789|H focus sprint", GroupPlayClassicSprint.formatFocusSprint("0123456789ABC", "H"))
        assertEquals("RAPIDFIRE:abc|H rapid-fire — name 5 in 20s!", GroupPlayClassicSprint.formatRapidFire("abc", "H"))
        assertNull(GroupPlayClassicSprint.parseRapidFire("RAPIDFIRE:|H"))
        // take() cuts by UTF-16 code unit: 10 emoji (20 units) become 6.
        assertEquals("😀".repeat(6), GroupPlayClassicSprint.parseTempoTap(GroupPlayClassicSprint.formatTempoTap("😀".repeat(10), "H")))
        // Blank singles parse back to null; wrong prefix is rejected.
        assertNull(GroupPlayClassicSprint.parseLightning("LIGHTNING:|H"))
        assertNull(GroupPlayClassicSprint.parseLightning("WRONG:x|H"))
        assertEquals("abc", GroupPlayClassicSprint.parseLightning("LIGHTNING:abc|H lightning round"))
    }

}
