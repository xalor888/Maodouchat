package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [ChatDraftPolicy] (checklist line 931,
 * 55th block). Test-only, zero production changes.
 *
 * Oracles are independent transcriptions of the five pure scheduling
 * predicates; the whitespace set they rely on (isBlank / isNullOrBlank)
 * was probed on the JDK: 28 UTF-16 code units are whitespace (U+00A0 /
 * U+2007 / U+202F count, U+0085 NEL does not). Random inputs use
 * Random(42), fresh per @Test.
 */
class ChatDraftPolicyFuzzTest {

// ---------- C1 ChatDraftPolicy oracle ----------

private fun canScheduleOracle(ownerUserId: String?, chatId: String?): Boolean =
    !ownerUserId.isNullOrBlank() && !chatId.isNullOrBlank()

private fun shouldPersistGenerationOracle(scheduled: Long, current: Long): Boolean =
    scheduled == current

private fun shouldWriteOracle(ownerUserId: String?, liveUserId: String?): Boolean =
    !ownerUserId.isNullOrBlank() && ownerUserId == liveUserId

private fun isClearRequestOracle(text: String): Boolean = text.isBlank()

private fun shouldApplyRestoredDraftOracle(
    hasUserEditedInput: Boolean,
    currentInput: String
): Boolean = !hasUserEditedInput && currentInput.isBlank()

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomNullableString(rnd: Random): String? =
    if (rnd.nextInt(4) == 0) null else randomString(rnd)

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

private fun randomTab(rnd: Random): Int = when (rnd.nextInt(10)) {
    0 -> -5; 1 -> -1; 2 -> 0; 3 -> 1; 4 -> 2; 5 -> 3; 6 -> 5; 7 -> 100
    8 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

private fun randomAlertContent(rnd: Random): String = when (rnd.nextInt(6)) {
    0 -> "CAPTURE_ALERT:" + randomString(rnd, 12) + "|" + randomString(rnd, 20)
    1 -> "CAPTURE_ALERT:" + randomString(rnd, 12)
    2 -> "CAPTURE_ALERT:" + randomString(rnd, 6) + "|" + randomString(rnd, 6) + "|" + randomString(rnd, 6)
    3 -> "CAPTURE_ALERT:"
    4 -> randomString(rnd)
    else -> randomString(rnd, 8) + "CAPTURE_ALERT:" + randomString(rnd, 8)
}

    @Test
    fun canScheduleRandom() {
        val rnd = Random(42)
        repeat(300) {
            val owner = randomNullableString(rnd)
            val chat = randomNullableString(rnd)
            assertEquals(canScheduleOracle(owner, chat), ChatDraftPolicy.canSchedule(owner, chat))
        }
    }

    @Test
    fun shouldPersistGenerationRandom() {
        val rnd = Random(42)
        repeat(300) {
            val a = randomLong(rnd)
            val b = if (rnd.nextInt(4) == 0) a else randomLong(rnd)
            assertEquals(shouldPersistGenerationOracle(a, b), ChatDraftPolicy.shouldPersistGeneration(a, b))
        }
    }

    @Test
    fun shouldWriteRandom() {
        val rnd = Random(42)
        repeat(300) {
            val owner = randomNullableString(rnd)
            val live = if (rnd.nextInt(3) == 0) owner else randomNullableString(rnd)
            assertEquals(shouldWriteOracle(owner, live), ChatDraftPolicy.shouldWrite(owner, live))
        }
    }

    @Test
    fun isClearRequestRandom() {
        val rnd = Random(42)
        repeat(300) {
            val text = randomString(rnd)
            assertEquals(isClearRequestOracle(text), ChatDraftPolicy.isClearRequest(text))
        }
    }

    @Test
    fun shouldApplyRestoredDraftRandom() {
        val rnd = Random(42)
        repeat(300) {
            val edited = rnd.nextBoolean()
            val cur = randomString(rnd)
            assertEquals(shouldApplyRestoredDraftOracle(edited, cur), ChatDraftPolicy.shouldApplyRestoredDraft(edited, cur))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // canSchedule: null / empty / blank owner or chat all refuse.
        assertEquals(false, ChatDraftPolicy.canSchedule(null, "c"))
        assertEquals(false, ChatDraftPolicy.canSchedule("", "c"))
        assertEquals(false, ChatDraftPolicy.canSchedule("   ", "c"))
        assertEquals(false, ChatDraftPolicy.canSchedule("\u00A0", "c"))
        assertEquals(false, ChatDraftPolicy.canSchedule("u", null))
        assertEquals(false, ChatDraftPolicy.canSchedule("u", ""))
        assertEquals(false, ChatDraftPolicy.canSchedule("u", "\u2007"))
        assertEquals(true, ChatDraftPolicy.canSchedule("u", "c"))
        // U+0085 NEL is NOT Kotlin whitespace (probed): it counts as content.
        assertEquals(true, ChatDraftPolicy.canSchedule("\u0085", "c"))
        assertEquals(true, ChatDraftPolicy.canSchedule("u", "\u0085"))
        // shouldPersistGeneration: plain Long equality, extremes included.
        assertEquals(true, ChatDraftPolicy.shouldPersistGeneration(350L, 350L))
        assertEquals(true, ChatDraftPolicy.shouldPersistGeneration(0L, 0L))
        assertEquals(true, ChatDraftPolicy.shouldPersistGeneration(Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(false, ChatDraftPolicy.shouldPersistGeneration(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(false, ChatDraftPolicy.shouldPersistGeneration(1L, 2L))
        // shouldWrite: a blank owner never matches, even against itself.
        assertEquals(true, ChatDraftPolicy.shouldWrite("u", "u"))
        assertEquals(false, ChatDraftPolicy.shouldWrite("u", "v"))
        assertEquals(false, ChatDraftPolicy.shouldWrite(null, "u"))
        assertEquals(false, ChatDraftPolicy.shouldWrite("u", null))
        assertEquals(false, ChatDraftPolicy.shouldWrite(" ", " "))
        assertEquals(false, ChatDraftPolicy.shouldWrite("\u00A0", "\u00A0"))
        assertEquals(true, ChatDraftPolicy.shouldWrite("\u0085", "\u0085"))
        // isClearRequest: blank incl. NBSP clears; NEL / ZWSP do not.
        assertEquals(true, ChatDraftPolicy.isClearRequest(""))
        assertEquals(true, ChatDraftPolicy.isClearRequest("   "))
        assertEquals(true, ChatDraftPolicy.isClearRequest("\u00A0"))
        assertEquals(true, ChatDraftPolicy.isClearRequest("\u2007\u202F"))
        assertEquals(false, ChatDraftPolicy.isClearRequest("\u0085"))
        assertEquals(false, ChatDraftPolicy.isClearRequest("\u200B"))
        assertEquals(false, ChatDraftPolicy.isClearRequest("x"))
        // shouldApplyRestoredDraft: any edit or non-blank field blocks it.
        assertEquals(true, ChatDraftPolicy.shouldApplyRestoredDraft(false, ""))
        assertEquals(true, ChatDraftPolicy.shouldApplyRestoredDraft(false, "  "))
        assertEquals(true, ChatDraftPolicy.shouldApplyRestoredDraft(false, "\u00A0"))
        assertEquals(false, ChatDraftPolicy.shouldApplyRestoredDraft(false, "x"))
        assertEquals(false, ChatDraftPolicy.shouldApplyRestoredDraft(false, "\u0085"))
        assertEquals(false, ChatDraftPolicy.shouldApplyRestoredDraft(true, ""))
        assertEquals(false, ChatDraftPolicy.shouldApplyRestoredDraft(true, "x"))
    }
}
