package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------- oracle ----------

// Kotlin trim(): drops leading/trailing Char.isWhitespace() chars.
// Measured on kotlinc 2.4.0 (TrimProbe2, fuzz-188): 0x09-0x0D, 0x1C-0x20,
// 0x00A0, 0x1680, 0x2000-0x200A, 0x2028-0x2029, 0x202F, 0x205F, 0x3000.
// U+0085 and U+FEFF are NOT trimmed, NBSP IS.
private fun ktTrim189(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i].isWhitespace()) i++
    while (j > i && s[j - 1].isWhitespace()) j--
    return s.substring(i, j)
}

// Lowercase for the generated alphabet (ASCII) plus the pinned non-ASCII
// code points (İ, ẞ, Kelvin, Angstrom) whose mappings were probe-verified.
private fun lower189(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            in 'A'..'Z' -> sb.append(c + 32)
            'İ' -> sb.append("i\u0307")
            'ẞ' -> sb.append('ß')
            '\u212A' -> sb.append('k')
            '\u212B' -> sb.append('å')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun normalizeReasonCodeOracle189(raw: String?): String? {
    val v = raw?.let { ktTrim189(lower189(it)).take(40) } ?: return null
    return v.takeIf { !isBlank189(it) }
}

private fun normalizeNoteOracle189(raw: String?): String? {
    val v = raw?.let { ktTrim189(it).take(500) } ?: return null
    return v.takeIf { !isBlank189(it) }
}

private fun isBlank189(s: String): Boolean {
    for (c in s) {
        if (!c.isWhitespace()) return false
    }
    return true
}

private val MUTE_TEMPLATES189 = listOf(
    Triple("flood", 1, false),
    Triple("off_topic", 6, false),
    Triple("harassment", 24, false),
    Triple("spam_link", 12, false),
    Triple("other", 1, true)
)

private val BAN_TEMPLATES189 = listOf(
    Triple("spam", 7, false),
    Triple("harassment", 14, false),
    Triple("scam", 30, false),
    Triple("illegal", 90, false),
    Triple("impersonation", 30, false),
    Triple("abuse_api", 7, false),
    Triple("other", 1, true)
)

private fun findMuteTemplateOracle189(code: String?): MuteTemplate? {
    val normalized = normalizeReasonCodeOracle189(code) ?: return null
    val t = MUTE_TEMPLATES189.firstOrNull { it.first == normalized } ?: return null
    return MuteTemplate(t.first, "", t.second, t.third)
}

private fun findTemplateOracle189(code: String?): ReasonTemplate? {
    val normalized = normalizeReasonCodeOracle189(code) ?: return null
    val t = BAN_TEMPLATES189.firstOrNull { it.first == normalized } ?: return null
    return ReasonTemplate(t.first, "", t.second, t.third)
}

private fun isKnownReasonCodeOracle189(code: String?): Boolean {
    val normalized = normalizeReasonCodeOracle189(code) ?: return false
    return normalized == "unban" || BAN_TEMPLATES189.any { it.first == normalized }
}

private fun mutedUntilFromHoursOracle189(hours: Int, nowMs: Long): Long {
    if (hours <= 0) return 0L
    return nowMs + hours.toLong() * 3_600_000L
}

private fun bannedUntilFromDaysOracle189(banDays: Int, nowMs: Long): Long {
    if (banDays <= 0) return 0L
    return nowMs + banDays.toLong() * 86_400_000L
}

private fun validateMuteOracle189(
    durationHours: Int,
    reasonCode: String?,
    note: String?
): DispositionValidation {
    if (durationHours < 0 || durationHours > 24 * 30) {
        return DispositionValidation.Invalid("禁言时长无效")
    }
    val noteNorm = normalizeNoteOracle189(note)
    if (durationHours == 0) {
        return DispositionValidation.Ok(
            reasonCode = "unmute",
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findMuteTemplateOracle189(reasonCode)
        ?: return DispositionValidation.Invalid("请选择禁言原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = 0
    )
}

private fun validateDispositionOracle189(
    banDays: Int,
    reasonCode: String?,
    note: String?
): DispositionValidation {
    if (banDays < 0 || banDays > 3650) {
        return DispositionValidation.Invalid("封禁天数无效")
    }
    val code = normalizeReasonCodeOracle189(reasonCode)
    val noteNorm = normalizeNoteOracle189(note)
    if (banDays == 0) {
        val effective = code ?: "unban"
        if (effective != "unban" && !isKnownReasonCodeOracle189(effective)) {
            return DispositionValidation.Invalid("未知解封原因码")
        }
        return DispositionValidation.Ok(
            reasonCode = if (effective == "unban") "unban" else effective,
            note = noteNorm,
            banDays = 0
        )
    }
    if (code == null || !isKnownReasonCodeOracle189(code) || code == "unban") {
        return DispositionValidation.Invalid("请选择封禁原因模板")
    }
    val template = findTemplateOracle189(code)
        ?: return DispositionValidation.Invalid("请选择封禁原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionValidation.Ok(
        reasonCode = code,
        note = noteNorm,
        banDays = banDays
    )
}

private fun auditDetailOracle189(bannedUntil: Long, reasonCode: String, note: String?): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "bannedUntil=$bannedUntil; reasonCode=$reasonCode$notePart"
}

private val REASON_POOL189 = listOf(
    "", " ", "spam", "SPAM", " Spam ", "flood", "FLOOD", "harassment",
    "other", "OTHER", " other ", "unknown_code", "unban", "UNBAN", " Unban ",
    "abuse_api", "scam", "illegal", "impersonation", "spam_link", "off_topic",
    "a".repeat(40), "a".repeat(41), "spam" + " ".repeat(50), "  ",
    "\u00A0spam\u00A0", "spam\n", "\tspam\t"
)
private val NOTE_POOL189 = listOf(
    null, "", "   ", "note", "  note  ", "备注", "x".repeat(499),
    "x".repeat(500), "x".repeat(501), "\u00A0", "a b"
)
private val MUTE_HOURS189 = listOf(0, 1, 6, 12, 24, 25, 720, 721, -1, 100, 719)
private val BAN_DAYS189 = listOf(0, 1, 7, 30, 90, 3650, 3651, -1, 100, 3649)
private val NOWS189 = listOf(0L, 1L, 1700000000000L, 1700000000000L + 123456L)

// ---------- tests ----------

class DispositionValidateFuzzTest {

    @Test
    fun validateMuteDifferential189() {
        val r = Random(189004)
        repeat(250) {
            val hours = MUTE_HOURS189.random(r)
            val reason = REASON_POOL189.random(r)
            val note = NOTE_POOL189.random(r)
            assertEquals(
                validateMuteOracle189(hours, reason, note),
                DispositionService.validateMute(hours, reason, note)
            )
        }
    }

    @Test
    fun validateDispositionDifferential189() {
        val r = Random(189005)
        repeat(250) {
            val days = BAN_DAYS189.random(r)
            val reason = REASON_POOL189.random(r)
            val note = NOTE_POOL189.random(r)
            assertEquals(
                validateDispositionOracle189(days, reason, note),
                DispositionService.validateDisposition(days, reason, note)
            )
            val now = NOWS189.random(r)
            assertEquals(
                bannedUntilFromDaysOracle189(days, now),
                DispositionService.bannedUntilFromDays(days, now)
            )
            val hours = MUTE_HOURS189.random(r)
            assertEquals(
                mutedUntilFromHoursOracle189(hours, now),
                DispositionService.mutedUntilFromHours(hours, now)
            )
            assertEquals(
                auditDetailOracle189(now, reason ?: "", note),
                DispositionService.auditDetail(now, reason ?: "", note)
            )
        }
    }

    @Test
    fun dispositionTemplatesPinned189() {
        assertEquals("flood", DispositionService.findMuteTemplate("FLOOD")?.code)
        assertEquals(1, DispositionService.findMuteTemplate("flood")?.durationHours)
        assertEquals(6, DispositionService.findMuteTemplate("off_topic")?.durationHours)
        assertNull(DispositionService.findMuteTemplate("spam"))
        assertNull(DispositionService.findMuteTemplate(null))
        assertNull(DispositionService.findMuteTemplate("  "))
        assertEquals("spam", DispositionService.findTemplate("SPAM")?.code)
        assertEquals(90, DispositionService.findTemplate("illegal")?.defaultDays)
        assertTrue(DispositionService.findTemplate("other")?.requiresCustomNote == true)
        assertNull(DispositionService.findTemplate("flood"))
        assertEquals(
            DispositionValidation.Invalid("该原因须填写备注"),
            DispositionService.validateMute(1, "other", null)
        )
        assertEquals(
            DispositionValidation.Invalid("该原因须填写备注"),
            DispositionService.validateDisposition(1, "other", "   ")
        )
        assertEquals(
            DispositionValidation.Ok("unmute", null, 0),
            DispositionService.validateMute(0, "spam", null)
        )
        assertEquals(
            DispositionValidation.Ok("spam", "note", 0),
            DispositionService.validateDisposition(0, "SPAM", "note")
        )
        assertEquals(
            DispositionValidation.Invalid("未知解封原因码"),
            DispositionService.validateDisposition(0, "nope", null)
        )
        assertEquals(0L, DispositionService.mutedUntilFromHours(0, 1700000000000L))
        assertEquals(
            1700000000000L + 3_600_000L,
            DispositionService.mutedUntilFromHours(1, 1700000000000L)
        )
        assertEquals(0L, DispositionService.bannedUntilFromDays(-5, 1700000000000L))
        assertEquals(
            1700000000000L + 7L * 86_400_000L,
            DispositionService.bannedUntilFromDays(7, 1700000000000L)
        )
        // Long overflow wraps in Kotlin: oracle and production must agree.
        val overflowNow = Long.MAX_VALUE - 100L
        assertEquals(
            bannedUntilFromDaysOracle189(1, overflowNow),
            DispositionService.bannedUntilFromDays(1, overflowNow)
        )
        assertEquals(
            "bannedUntil=5; reasonCode=spam; note=x",
            DispositionService.auditDetail(5L, "spam", "x")
        )
        assertEquals(
            "bannedUntil=5; reasonCode=spam",
            DispositionService.auditDetail(5L, "spam", null)
        )
    }
}
