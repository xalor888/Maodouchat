// Fuzz / random-oracle tests for MessageAiActionPolicy
// (checklist line 931, 131st block). Test-only, zero production changes.
// The oracle is hand-written from the production source: trim() then
// ROOT-locale uppercase() decides the action list; the two boolean
// gates are plain conjunctions. No MessageAiActionPolicy fuzz test
// existed before this block (content-grep dedup 0 hits on all 137
// remote branches, main's 127 FuzzTest files, and all 134 open PR
// titles/bodies).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun maiaActionsOracle(messageType: String?, hasTranscript: Boolean): List<MessageAiAction> {
    return when (messageType?.trim()?.uppercase()) {
        "TEXT", "MARKDOWN", "SYSTEM" -> listOf(MessageAiAction.TRANSLATE)
        "VOICE" -> if (hasTranscript) emptyList() else listOf(MessageAiAction.TRANSCRIBE)
        "IMAGE", "GIF" -> listOf(MessageAiAction.ANALYZE_IMAGE)
        "FILE" -> listOf(MessageAiAction.ANALYZE_FILE)
        else -> emptyList()
    }
}

private fun maiaHasOracle(messageType: String?, hasTranscript: Boolean): Boolean =
    maiaActionsOracle(messageType, hasTranscript).isNotEmpty()

private fun maiaMenuOracle(isBusy: Boolean, isUpdatingSetting: Boolean): Boolean =
    !isBusy && !isUpdatingSetting

private fun maiaRun3Oracle(masterEnabled: Boolean, chatAiEnabled: Boolean, isBusy: Boolean): Boolean =
    masterEnabled && chatAiEnabled && !isBusy

private fun maiaRun2Oracle(chatAiEnabled: Boolean, isBusy: Boolean): Boolean =
    maiaRun3Oracle(masterEnabled = true, chatAiEnabled = chatAiEnabled, isBusy = isBusy)

// ---------- generators (mirrored by Oracle131 GEN, seed 131001) ----------

private val MAIA_TYPE_POOL = arrayOf(
    "", " ", "TEXT", "text", "Text", "MARKDOWN", "markdown", "VOICE", "voice",
    "IMAGE", "image", "GIF", "gif", "FILE", "file", "SYSTEM", "system",
    "VIDEO", "AUDIO", "  text  ", "\tVOICE\n", "texT", "TEXT ", " text",
    "TEXTX", "TEX", "TEXT ", "İ", "ß", "ﬀ", "Σ", "ς", "ſ", "ǆ", "ᾀ",
    "ﬁle", "ﬁ", "IMAGÉ", "tExT\uD800", "VOICE\uDC00", "TEXT ", "TEXT​"
)

private fun genMaiaType(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MAIA_TYPE_POOL[rnd.nextInt(MAIA_TYPE_POOL.size)]
    else randomText(rnd, 10)

private fun genMaiaTypeNullable(rnd: Random): String? =
    if (rnd.nextInt(6) == 0) null else genMaiaType(rnd)

class MessageAiActionPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Text-like types translate; case and surrounding whitespace do not matter.
        assertEquals(listOf(MessageAiAction.TRANSLATE), MessageAiActionPolicy.contextActionsFor("TEXT"))
        assertEquals(listOf(MessageAiAction.TRANSLATE), MessageAiActionPolicy.contextActionsFor("text"))
        assertEquals(listOf(MessageAiAction.TRANSLATE), MessageAiActionPolicy.contextActionsFor("  text  "))
        assertEquals(listOf(MessageAiAction.TRANSLATE), MessageAiActionPolicy.contextActionsFor("MARKDOWN"))
        assertEquals(listOf(MessageAiAction.TRANSLATE), MessageAiActionPolicy.contextActionsFor("SYSTEM"))
        // Voice transcribes only without a transcript.
        assertEquals(listOf(MessageAiAction.TRANSCRIBE), MessageAiActionPolicy.contextActionsFor("VOICE"))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("VOICE", hasTranscript = true))
        assertEquals(listOf(MessageAiAction.TRANSCRIBE), MessageAiActionPolicy.contextActionsFor("voice"))
        // Media types analyze; anything else (or null) offers nothing.
        assertEquals(listOf(MessageAiAction.ANALYZE_IMAGE), MessageAiActionPolicy.contextActionsFor("IMAGE"))
        assertEquals(listOf(MessageAiAction.ANALYZE_IMAGE), MessageAiActionPolicy.contextActionsFor("GIF"))
        assertEquals(listOf(MessageAiAction.ANALYZE_FILE), MessageAiActionPolicy.contextActionsFor("FILE"))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor(null))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor(""))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("VIDEO"))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("TEXTX"))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("TEX"))
        // Uppercase expansions decide: ß -> SS (no match), ﬁ -> FI so "ﬁle" is FILE.
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("ß"))
        assertEquals(listOf(MessageAiAction.ANALYZE_FILE), MessageAiActionPolicy.contextActionsFor("ﬁle"))
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("ﬀ"))
        // A combining mark after IMAGE is not trimmed away and breaks the match.
        assertEquals(emptyList(), MessageAiActionPolicy.contextActionsFor("IMAGÉ"))
        // hasContextAiActions mirrors the list being non-empty.
        assertTrue(MessageAiActionPolicy.hasContextAiActions("TEXT"))
        assertFalse(MessageAiActionPolicy.hasContextAiActions(null))
        assertFalse(MessageAiActionPolicy.hasContextAiActions("VIDEO"))
        assertFalse(MessageAiActionPolicy.hasContextAiActions("VOICE", hasTranscript = true))
        // Composer menu opens only when idle and not updating a setting.
        assertTrue(MessageAiActionPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = false))
        assertFalse(MessageAiActionPolicy.canOpenComposerMenu(isBusy = true, isUpdatingSetting = false))
        assertFalse(MessageAiActionPolicy.canOpenComposerMenu(isBusy = false, isUpdatingSetting = true))
        assertFalse(MessageAiActionPolicy.canOpenComposerMenu(isBusy = true, isUpdatingSetting = true))
        // Run gate: master + chat enabled and idle; the 2-arg form assumes master on.
        assertTrue(MessageAiActionPolicy.canRunContextAction(chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(chatAiEnabled = false, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(chatAiEnabled = true, isBusy = true))
        assertTrue(MessageAiActionPolicy.canRunContextAction(masterEnabled = true, chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(masterEnabled = false, chatAiEnabled = true, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(masterEnabled = true, chatAiEnabled = false, isBusy = false))
        assertFalse(MessageAiActionPolicy.canRunContextAction(masterEnabled = true, chatAiEnabled = true, isBusy = true))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle131.genMaia consumption order exactly, so seed
        // 131001 replays the same 1500 GEN lines drive131.py ran.
        val rnd = Random(131001)
        repeat(1500) {
            when (rnd.nextInt(5)) {
                0 -> {
                    val type = genMaiaTypeNullable(rnd)
                    val tr = rnd.nextBoolean()
                    assertEquals(maiaActionsOracle(type, tr), MessageAiActionPolicy.contextActionsFor(type, tr))
                }
                1 -> {
                    val type = genMaiaTypeNullable(rnd)
                    val tr = rnd.nextBoolean()
                    assertEquals(maiaHasOracle(type, tr), MessageAiActionPolicy.hasContextAiActions(type, tr))
                }
                2 -> {
                    val busy = rnd.nextBoolean()
                    val updating = rnd.nextBoolean()
                    assertEquals(maiaMenuOracle(busy, updating), MessageAiActionPolicy.canOpenComposerMenu(busy, updating))
                }
                3 -> {
                    val chatAi = rnd.nextBoolean()
                    val busy = rnd.nextBoolean()
                    assertEquals(maiaRun2Oracle(chatAi, busy), MessageAiActionPolicy.canRunContextAction(chatAi, busy))
                }
                else -> {
                    val master = rnd.nextBoolean()
                    val chatAi = rnd.nextBoolean()
                    val busy = rnd.nextBoolean()
                    assertEquals(
                        maiaRun3Oracle(master, chatAi, busy),
                        MessageAiActionPolicy.canRunContextAction(master, chatAi, busy)
                    )
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool type (plus null) through both actions entry points.
        for (type in MAIA_TYPE_POOL.toList() + listOf<String?>(null)) {
            for (tr in listOf(false, true)) {
                assertEquals(maiaActionsOracle(type, tr), MessageAiActionPolicy.contextActionsFor(type, tr))
                assertEquals(maiaHasOracle(type, tr), MessageAiActionPolicy.hasContextAiActions(type, tr))
            }
        }
        // Whitespace/control singles as the whole type.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(maiaActionsOracle(s, false), MessageAiActionPolicy.contextActionsFor(s, false))
            assertEquals(maiaActionsOracle(s, true), MessageAiActionPolicy.contextActionsFor(s, true))
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(maiaActionsOracle(s, false), MessageAiActionPolicy.contextActionsFor(s, false))
            assertEquals(maiaActionsOracle(s, true), MessageAiActionPolicy.contextActionsFor(s, true))
        }
        // Boolean gates exhaustively.
        for (busy in listOf(false, true)) {
            for (updating in listOf(false, true)) {
                assertEquals(maiaMenuOracle(busy, updating), MessageAiActionPolicy.canOpenComposerMenu(busy, updating))
            }
        }
        for (chatAi in listOf(false, true)) {
            for (busy in listOf(false, true)) {
                assertEquals(maiaRun2Oracle(chatAi, busy), MessageAiActionPolicy.canRunContextAction(chatAi, busy))
            }
        }
        for (master in listOf(false, true)) {
            for (chatAi in listOf(false, true)) {
                for (busy in listOf(false, true)) {
                    assertEquals(
                        maiaRun3Oracle(master, chatAi, busy),
                        MessageAiActionPolicy.canRunContextAction(master, chatAi, busy)
                    )
                }
            }
        }
    }
}
