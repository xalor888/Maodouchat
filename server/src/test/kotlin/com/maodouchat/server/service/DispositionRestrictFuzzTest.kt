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
private fun isTrimWs190(c: Char): Boolean =
    c in '\u0009'..'\u000D' || c in '\u001C'..'\u0020' || c == '\u00A0' ||
        c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' ||
        c == '\u202F' || c == '\u205F' || c == '\u3000'

private fun ktTrim190(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && isTrimWs190(s[i])) i++
    while (j > i && isTrimWs190(s[j - 1])) j--
    return s.substring(i, j)
}

private fun isBlank190(s: String): Boolean {
    for (c in s) {
        if (!isTrimWs190(c)) return false
    }
    return true
}

// Lowercase for the generated alphabet only (ASCII + pinned non-ASCII).
// Per-unit mappings verified against probe truth (fuzz-189 norm probe,
// 800 code points): İ -> i + U+0307, ẞ -> ß, Kelvin -> k, Angstrom -> å,
// Σ -> σ, then the final-sigma scan below may flip it to ς.
private fun lowerUnit190(c: Char): String = when (c) {
    in 'A'..'Z' -> (c + 32).toString()
    '\u00C9' -> "\u00E9"
    '\u00DC' -> "\u00FC"
    '\u0391' -> "\u03B1"
    '\u03A9' -> "\u03C9"
    '\u0130' -> "i\u0307"
    '\u1E9E' -> "\u00DF"
    '\u212A' -> "k"
    '\u212B' -> "\u00E5"
    '\u03A3' -> "\u03C3"
    else -> c.toString()
}

// Cased (Lu/Ll/Lt) over the generated alphabet; used by the sigma scan.
private fun isCased190(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c == '\u00C9' || c == '\u00E9' ||
        c == '\u00DC' || c == '\u00FC' || c == '\u00DF' || c == '\u0391' ||
        c == '\u03B1' || c == '\u03A9' || c == '\u03C9' || c == '\u03A3' ||
        c == '\u03C3' || c == '\u03C2' || c == '\u0130' || c == '\u1E9E' ||
        c == '\u212A' || c == '\u212B' || c == '\u00E5'

// Case-ignorable over the generated alphabet (all Nd + U+00AA/U+00BA/
// U+01BB/U+01C0-U+01C3, SigmaProbe189). Java lowercase() scans past
// these in both directions when deciding final sigma.
private fun isIgnorable190(c: Char): Boolean =
    c in '0'..'9' || c in '\u0660'..'\u0669' || c in '\uFF10'..'\uFF19' ||
        c == '\u00AA' || c == '\u00BA' || c == '\u01BB' || c in '\u01C0'..'\u01C3'

private fun lower190(s: String): String {
    val units = ArrayList<Int>()
    val src = ArrayList<Int>()
    for (i in s.indices) {
        val low = lowerUnit190(s[i])
        for (ch in low) {
            units.add(ch.code)
            src.add(i)
        }
    }
    for (pos in units.indices) {
        if (units[pos] != 0x03C3) continue
        if (s[src[pos]] != '\u03A3') continue
        var j = src[pos] - 1
        while (j >= 0 && isIgnorable190(s[j])) j--
        val before = j >= 0 && isCased190(s[j])
        j = src[pos] + 1
        while (j < s.length && isIgnorable190(s[j])) j++
        val after = j < s.length && isCased190(s[j])
        if (before && !after) units[pos] = 0x03C2
    }
    return String(units.map { it.toChar() }.toCharArray())
}

private fun normalizeReasonCodeOracle190(raw: String?): String? {
    val v = raw?.let { ktTrim190(lower190(it)).take(40) } ?: return null
    return v.takeIf { !isBlank190(it) }
}

private fun normalizeNoteOracle190(raw: String?): String? {
    val v = raw?.let { ktTrim190(it).take(500) } ?: return null
    return v.takeIf { !isBlank190(it) }
}

private val MSG_TEMPLATES190 = listOf(
    Triple("spam_chat", 1, false),
    Triple("harassment_chat", 3, false),
    Triple("scam_chat", 7, false),
    Triple("illegal_chat", 14, false),
    Triple("abuse_api", 3, false),
    Triple("other", 1, true)
)

private val POST_TEMPLATES190 = listOf(
    Triple("spam_feed", 1, false),
    Triple("harassment_feed", 3, false),
    Triple("scam_feed", 7, false),
    Triple("illegal_feed", 14, false),
    Triple("impersonation_feed", 7, false),
    Triple("other", 1, true)
)

private fun findMessageRestrictTemplateOracle190(code: String?): MessageRestrictTemplate? {
    val normalized = normalizeReasonCodeOracle190(code) ?: return null
    val t = MSG_TEMPLATES190.firstOrNull { it.first == normalized } ?: return null
    return MessageRestrictTemplate(t.first, "", t.second, t.third)
}

private fun findPostRestrictTemplateOracle190(code: String?): PostRestrictTemplate? {
    val normalized = normalizeReasonCodeOracle190(code) ?: return null
    val t = POST_TEMPLATES190.firstOrNull { it.first == normalized } ?: return null
    return PostRestrictTemplate(t.first, "", t.second, t.third)
}

private fun messageRestrictedUntilFromDaysOracle190(days: Int, nowMs: Long): Long {
    if (days <= 0) return 0L
    return nowMs + days.toLong() * 86_400_000L
}

private fun postRestrictedUntilFromDaysOracle190(days: Int, nowMs: Long): Long {
    if (days <= 0) return 0L
    return nowMs + days.toLong() * 86_400_000L
}

private fun validateMessageRestrictOracle190(
    durationDays: Int,
    reasonCode: String?,
    note: String?
): DispositionValidation {
    if (durationDays < 0 || durationDays > 90) {
        return DispositionValidation.Invalid("invalid message restrict duration")
    }
    val noteNorm = normalizeNoteOracle190(note)
    if (durationDays == 0) {
        val code = normalizeReasonCodeOracle190(reasonCode) ?: "unrestrict_messages"
        if (code != "unrestrict_messages" && findMessageRestrictTemplateOracle190(code) == null) {
            return DispositionValidation.Invalid("unknown unrestrict messages reason")
        }
        return DispositionValidation.Ok(
            reasonCode = if (code == "unrestrict_messages") "unrestrict_messages" else code,
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findMessageRestrictTemplateOracle190(reasonCode)
        ?: return DispositionValidation.Invalid("unknown message restrict reason")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionValidation.Invalid("note required for this reason")
    }
    return DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = 0
    )
}

private fun validatePostRestrictOracle190(
    durationDays: Int,
    reasonCode: String?,
    note: String?
): DispositionValidation {
    if (durationDays < 0 || durationDays > 90) {
        return DispositionValidation.Invalid("禁动态天数无效")
    }
    val noteNorm = normalizeNoteOracle190(note)
    if (durationDays == 0) {
        val code = normalizeReasonCodeOracle190(reasonCode) ?: "unrestrict_posts"
        if (code != "unrestrict_posts" && findPostRestrictTemplateOracle190(code) == null) {
            return DispositionValidation.Invalid("未知解除禁动态原因码")
        }
        return DispositionValidation.Ok(
            reasonCode = if (code == "unrestrict_posts") "unrestrict_posts" else code,
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findPostRestrictTemplateOracle190(reasonCode)
        ?: return DispositionValidation.Invalid("请选择禁动态原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = durationDays
    )
}

private fun auditMessageRestrictDetailOracle190(
    messageRestrictedUntil: Long,
    reasonCode: String,
    note: String?
): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "messageRestrictedUntil=$messageRestrictedUntil; reasonCode=$reasonCode$notePart"
}

private fun auditPostRestrictDetailOracle190(
    postRestrictedUntil: Long,
    reasonCode: String,
    note: String?
): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "postRestrictedUntil=$postRestrictedUntil; reasonCode=$reasonCode$notePart"
}

private val REASON_POOL190 = listOf(
    "", " ", "spam_chat", "SPAM_CHAT", " Spam_Chat ", "harassment_chat",
    "scam_chat", "illegal_chat", "abuse_api", "other", "OTHER", " other ",
    "spam_feed", "spam", "unrestrict_messages", "UNRESTRICT_MESSAGES",
    "unrestrict_posts", "unknown_code", "flood", "harassment_feed",
    "impersonation_feed", "a".repeat(40), "a".repeat(41),
    "spam_chat" + " ".repeat(50), "  ",
    "\u00A0spam_chat\u00A0", "spam_chat\n", "\tspam_chat\t",
    "ΑΣ", "ΑΣ9Α", "A9Σ", "ΣA", "Α9Σ", "İ", "ẞ", "\u212A", "\u212B",
    "É", "spam_chat\u0660", "spam_chat\uFF19", "spam\u00A0chat"
)
private val NOTE_POOL190 = listOf(
    null, "", "   ", "note", "  note  ", "备注", "x".repeat(499),
    "x".repeat(500), "x".repeat(501), "\u00A0", "a b",
    "note\nwith newline", "İ note Σ", "😀 emoji", "a\uD800b"
)
private val RESTRICT_DAYS190 = listOf(0, 1, 3, 7, 14, 90, 91, -1, 100, 89)
private val NOWS190 = listOf(0L, 1L, 1700000000000L, 1700000000000L + 123456L)

private const val REASON_ALPHABET190 =
    "abcxyz_ -ABCXYZ019İẞΣσς\u212A\u212BÉéÜüßΑΩ\u0660\uFF19 \t\n\u00A0"
private const val NOTE_ALPHABET190 =
    "abcXYZ 019\n\t备注😀éİΣ\u00A0"

private fun randomReason190(r: Random): String? {
    if (r.nextInt(4) == 0) return REASON_POOL190.random(r)
    if (r.nextInt(12) == 0) return null
    val n = r.nextInt(0, 45)
    return buildString {
        repeat(n) { append(REASON_ALPHABET190.random(r)) }
    }
}

private fun randomNote190(r: Random): String? {
    if (r.nextInt(4) == 0) return NOTE_POOL190.random(r)
    val n = r.nextInt(0, 520)
    return buildString {
        repeat(n) { append(NOTE_ALPHABET190.random(r)) }
    }
}

private fun templateSig190(t: MessageRestrictTemplate?): Triple<String, Int, Boolean>? =
    t?.let { Triple(it.code, it.defaultDays, it.requiresCustomNote) }

private fun postTemplateSig190(t: PostRestrictTemplate?): Triple<String, Int, Boolean>? =
    t?.let { Triple(it.code, it.defaultDays, it.requiresCustomNote) }

// ---------- tests ----------

class DispositionRestrictFuzzTest {

    @Test
    fun validateMessageRestrictDifferential190() {
        val r = Random(190001)
        repeat(250) {
            val days = RESTRICT_DAYS190.random(r)
            val reason = randomReason190(r)
            val note = randomNote190(r)
            assertEquals(
                validateMessageRestrictOracle190(days, reason, note),
                DispositionService.validateMessageRestrict(days, reason, note)
            )
            assertEquals(
                templateSig190(findMessageRestrictTemplateOracle190(reason)),
                templateSig190(DispositionService.findMessageRestrictTemplate(reason))
            )
            val now = NOWS190.random(r)
            assertEquals(
                messageRestrictedUntilFromDaysOracle190(days, now),
                DispositionService.messageRestrictedUntilFromDays(days, now)
            )
            assertEquals(
                auditMessageRestrictDetailOracle190(now, reason ?: "", note),
                DispositionService.auditMessageRestrictDetail(now, reason ?: "", note)
            )
        }
    }

    @Test
    fun validatePostRestrictDifferential190() {
        val r = Random(190002)
        repeat(250) {
            val days = RESTRICT_DAYS190.random(r)
            val reason = randomReason190(r)
            val note = randomNote190(r)
            assertEquals(
                validatePostRestrictOracle190(days, reason, note),
                DispositionService.validatePostRestrict(days, reason, note)
            )
            assertEquals(
                postTemplateSig190(findPostRestrictTemplateOracle190(reason)),
                postTemplateSig190(DispositionService.findPostRestrictTemplate(reason))
            )
            val now = NOWS190.random(r)
            assertEquals(
                postRestrictedUntilFromDaysOracle190(days, now),
                DispositionService.postRestrictedUntilFromDays(days, now)
            )
            assertEquals(
                auditPostRestrictDetailOracle190(now, reason ?: "", note),
                DispositionService.auditPostRestrictDetail(now, reason ?: "", note)
            )
        }
    }

    @Test
    fun dispositionRestrictPinned190() {
        assertEquals("spam_chat", DispositionService.findMessageRestrictTemplate("SPAM_CHAT")?.code)
        assertEquals(1, DispositionService.findMessageRestrictTemplate("spam_chat")?.defaultDays)
        assertEquals(14, DispositionService.findMessageRestrictTemplate("illegal_chat")?.defaultDays)
        assertTrue(DispositionService.findMessageRestrictTemplate("other")?.requiresCustomNote == true)
        assertNull(DispositionService.findMessageRestrictTemplate("spam_feed"))
        assertNull(DispositionService.findMessageRestrictTemplate(null))
        assertNull(DispositionService.findMessageRestrictTemplate("  "))
        assertEquals("spam_feed", DispositionService.findPostRestrictTemplate("SPAM_FEED")?.code)
        assertEquals(7, DispositionService.findPostRestrictTemplate("impersonation_feed")?.defaultDays)
        assertTrue(DispositionService.findPostRestrictTemplate("other")?.requiresCustomNote == true)
        assertNull(DispositionService.findPostRestrictTemplate("spam_chat"))
        assertEquals(
            DispositionValidation.Invalid("note required for this reason"),
            DispositionService.validateMessageRestrict(1, "other", null)
        )
        assertEquals(
            DispositionValidation.Invalid("该原因须填写备注"),
            DispositionService.validatePostRestrict(1, "other", "   ")
        )
        assertEquals(
            DispositionValidation.Ok("spam_chat", null, 0),
            DispositionService.validateMessageRestrict(0, "spam_chat", null)
        )
        assertEquals(
            DispositionValidation.Ok("unrestrict_messages", null, 0),
            DispositionService.validateMessageRestrict(0, null, null)
        )
        assertEquals(
            DispositionValidation.Ok("spam_chat", "note", 0),
            DispositionService.validateMessageRestrict(0, "SPAM_CHAT", "note")
        )
        assertEquals(
            DispositionValidation.Invalid("unknown unrestrict messages reason"),
            DispositionService.validateMessageRestrict(0, "nope", null)
        )
        assertEquals(
            DispositionValidation.Ok("unrestrict_posts", null, 0),
            DispositionService.validatePostRestrict(0, null, null)
        )
        assertEquals(
            DispositionValidation.Invalid("未知解除禁动态原因码"),
            DispositionService.validatePostRestrict(0, "nope", null)
        )
        // Post restrict keeps the duration in banDays; message restrict always 0.
        assertEquals(
            DispositionValidation.Ok("spam_feed", "note", 7),
            DispositionService.validatePostRestrict(7, "spam_feed", "note")
        )
        assertEquals(
            DispositionValidation.Ok("spam_chat", "note", 0),
            DispositionService.validateMessageRestrict(7, "spam_chat", "note")
        )
        assertEquals(
            DispositionValidation.Invalid("invalid message restrict duration"),
            DispositionService.validateMessageRestrict(91, "spam_chat", null)
        )
        assertEquals(
            DispositionValidation.Invalid("禁动态天数无效"),
            DispositionService.validatePostRestrict(-1, "spam_feed", null)
        )
        assertEquals(0L, DispositionService.messageRestrictedUntilFromDays(0, 1700000000000L))
        assertEquals(
            1700000000000L + 3L * 86_400_000L,
            DispositionService.messageRestrictedUntilFromDays(3, 1700000000000L)
        )
        assertEquals(0L, DispositionService.postRestrictedUntilFromDays(-5, 1700000000000L))
        // Long overflow wraps in Kotlin: oracle and production must agree.
        val overflowNow = Long.MAX_VALUE - 100L
        assertEquals(
            messageRestrictedUntilFromDaysOracle190(1, overflowNow),
            DispositionService.messageRestrictedUntilFromDays(1, overflowNow)
        )
        assertEquals(
            postRestrictedUntilFromDaysOracle190(90, overflowNow),
            DispositionService.postRestrictedUntilFromDays(90, overflowNow)
        )
        assertEquals(
            "messageRestrictedUntil=5; reasonCode=spam_chat; note=x",
            DispositionService.auditMessageRestrictDetail(5L, "spam_chat", "x")
        )
        assertEquals(
            "messageRestrictedUntil=5; reasonCode=spam_chat",
            DispositionService.auditMessageRestrictDetail(5L, "spam_chat", null)
        )
        assertEquals(
            "postRestrictedUntil=5; reasonCode=spam_feed; note=x",
            DispositionService.auditPostRestrictDetail(5L, "spam_feed", "x")
        )
        assertEquals(
            "postRestrictedUntil=5; reasonCode=spam_feed",
            DispositionService.auditPostRestrictDetail(5L, "spam_feed", null)
        )
        // Final sigma: oracle and production must agree on the scan rule.
        assertEquals(
            normalizeReasonCodeOracle190("ΑΣ9Α"),
            DispositionService.normalizeReasonCode("ΑΣ9Α")
        )
        assertEquals(
            normalizeReasonCodeOracle190("A9Σ"),
            DispositionService.normalizeReasonCode("A9Σ")
        )
        assertEquals(
            normalizeReasonCodeOracle190("ΣA"),
            DispositionService.normalizeReasonCode("ΣA")
        )
    }
}
