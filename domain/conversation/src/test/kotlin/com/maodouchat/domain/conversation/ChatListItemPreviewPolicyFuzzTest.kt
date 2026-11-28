// Fuzz / random-oracle tests for ChatListItemPreviewPolicy.resolveMessagePreview + resolveFinalPreview (checklist line 931,
// 119th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. resolveMessagePreview / resolveFinalPreview: list-row preview + final preview priority.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.MessageType

class ChatListItemPreviewPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- block-119 common codec helpers ----------

// Local hex encoder (same codec as Parse119.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse119.kt, so the codec lives here.
private fun encSpecStr(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun encNullableSpecStr(s: String?): String =
    if (s == null) "N" else "S" + encSpecStr(s)

private fun decSpecStr(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4)
    for (i in chars.indices) {
        chars[i] = h.substring(i * 4, i * 4 + 4).toInt(16).toChar()
    }
    return String(chars)
}

private fun decNullableSpecStr(f: String): String? =
    if (f == "N") null else decSpecStr(f.substring(1))

// Id-set spec (shared by the CMI and CLS clusters): ids joined by
// ";" with each id hex-encoded; empty set is "". Iteration order is
// the LinkedHashSet insertion order on both sides.
private fun idSetSpecOf(set: Set<String>): String =
    set.joinToString(";") { encSpecStr(it) }

private fun idSetOfSpec(spec: String): Set<String> =
    if (spec.isEmpty()) emptySet()
    else spec.split(";").map { decSpecStr(it) }.toSet()

// Hand-written lastIndexOf over UTF-16 units. Never lastIndexOf().
private fun lastIndexOfOracle(s: String, needle: String): Int {
    if (needle.isEmpty()) return s.length
    if (needle.length > s.length) return -1
    var i = s.length - needle.length
    while (i >= 0) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return i
        i--
    }
    return -1
}

// Case-insensitive startsWith, per-char fold like
// regionMatches(ignoreCase = true). Exact for every input.
private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

// ---------- ChatListItemPreviewPolicy oracle ----------

// Hand-written trimStart: strip leading UTF-16 units in the probed
// 28-unit Kotlin whitespace set. Never trimStart().
private fun trimStartOracle(s: String): String {
    var lo = 0
    while (lo < s.length && s[lo].code in BLANK_UNITS_ORACLE) lo++
    return s.substring(lo)
}

private val CIP_NUMERIC_DECIMAL = Regex("&#(\\d{1,7});")
private val CIP_NUMERIC_HEX = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)
private val CIP_CONTACT_CARD_MARKER_RE = Regex("\\n?\\[contactUser:[^\\]]+\\]")

private fun cipUnescapeOracle(raw: String): String {
    if (raw.isEmpty() || !containsOracle(raw, "&")) return raw
    var out = raw
    out = CIP_NUMERIC_DECIMAL.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = CIP_NUMERIC_HEX.replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

private val CIP_LEGACY_PLACEHOLDERS = setOf(
    "无法解密",
    "解密失败",
    "群密钥缺失",
    "密钥缺失",
    "decrypt failed",
    "session missing",
    "identity changed",
    "[无法解密的消息]",
    "[无法解密的群聊消息]",
    "[加密消息]",
    "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]",
    "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]",
    "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]",
    "[无法解密的 gif]",
    "[无法解密的贴纸]",
    "[无法解密的位置]",
    "[无法解密的视频]",
    "[无法解密的语音]",
    "[无法解密的文件]",
    "[unable to decrypt message]",
    "[unable to decrypt group message]",
    "[encrypted message]",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group epoch. refresh group info]",
    "[unable to decrypt image]",
    "[unable to decrypt gif]",
    "[unable to decrypt sticker]",
    "[unable to decrypt location]",
    "[unable to decrypt video]",
    "[unable to decrypt voice message]",
    "[unable to decrypt file]"
)

private fun cipIsPlaceholderOracle(content: String): Boolean {
    val normalized = lowercaseOracle(trimOracle(content))
    if (normalized.isEmpty()) return false
    return normalized in CIP_LEGACY_PLACEHOLDERS
}

private fun cipIsLocalMediaUriOracle(content: String): Boolean {
    val t = trimOracle(content)
    return t.startsWith("file:") || t.startsWith("content:") ||
        t.startsWith("maodou-attachment://")
}

private fun cipIsSignalWireEnvelopeOracle(content: String): Boolean {
    val t = trimStartOracle(content)
    if (!t.startsWith("{") && !t.startsWith("[{")) return false
    if (containsOracle(t, "signal-")) {
        return containsOracle(t, "\"algorithm\"") ||
            containsOracle(t, "\"distributionMessage\"") ||
            containsOracle(t, "\"ciphertext\"") || containsOracle(t, "\"senderKey")
    }
    val hasCiphertext = containsOracle(t, "\"ciphertext\"")
    val hasSenderDevice = containsOracle(t, "\"senderDeviceId\"")
    val hasEntries = containsOracle(t, "\"entries\"")
    val hasCiphertextType = containsOracle(t, "\"ciphertextType\"")
    val hasDistribution = containsOracle(t, "\"distributionMessage\"")
    return hasDistribution ||
        (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
}

private fun cipIsUrlOnlyOracle(text: String): Boolean {
    val t = trimOracle(text)
    if (t.isEmpty() || containsOracle(t, " ") || containsOracle(t, "\n")) return false
    return startsWithIgnoreCaseOracle(t, "http://") ||
        startsWithIgnoreCaseOracle(t, "https://") ||
        startsWithIgnoreCaseOracle(t, "www.")
}

private fun cipIsLeftoverCipherOrJoinUrlOracle(url: String): Boolean {
    val lower = lowercaseOracle(url)
    return containsOracle(lower, "/api/attachments") ||
        containsOracle(lower, "maodou-attachment") ||
        containsOracle(lower, "ciphertext") ||
        containsOracle(lower, "senderdeviceid")
}

private fun cipLooksLikeLeftoverGarbageOracle(content: String): Boolean {
    val t = trimOracle(content)
    if (isBlankOracle(t)) return true
    if (cipIsLocalMediaUriOracle(t)) return true
    if (cipIsSignalWireEnvelopeOracle(t)) return true
    if (t.startsWith("eyJ") && t.length >= 40) return true
    if (containsIgnoreCaseOracle(t, "<meta>") || containsIgnoreCaseOracle(t, "</meta>")) return true
    if (cipIsPlaceholderOracle(t)) return true
    if (cipIsUrlOnlyOracle(t) && cipIsLeftoverCipherOrJoinUrlOracle(t)) return true
    return false
}

private fun cipVisiblePreviewTextOracle(raw: String): String {
    val withoutMeta = lastIndexOfOracle(raw, "<meta>").let { idx ->
        if (idx < 0) raw else raw.substring(0, idx)
    }.let { trimOracle(it) }
    if (isBlankOracle(withoutMeta)) return ""
    if (cipIsLocalMediaUriOracle(withoutMeta)) return ""
    return cipUnescapeOracle(withoutMeta)
}

private fun cipListVisibleTextOracle(raw: String, encryptedPlaceholder: String): String {
    val body = cipVisiblePreviewTextOracle(raw)
    if (isBlankOracle(body) || cipLooksLikeLeftoverGarbageOracle(body)) return encryptedPlaceholder
    return body
}

private fun cipStripMarkerOracle(text: String): String =
    CIP_CONTACT_CARD_MARKER_RE.replace(text, "")

private fun cipMsgPreviewOracle(
    isLocked: Boolean,
    lockedPreview: String,
    secretBlocked: Boolean,
    secretPreview: String,
    lastMessageType: MessageType,
    lastMessage: String,
    nudgeLabel: String,
    mediaLabel: String?,
    encryptedPlaceholder: String
): String {
    if (isLocked) return lockedPreview
    if (secretBlocked) return secretPreview
    if (lastMessageType == MessageType.NUDGE) return nudgeLabel
    mediaLabel?.let { return it }
    val stripped = cipListVisibleTextOracle(lastMessage, encryptedPlaceholder)
    return if (stripped == encryptedPlaceholder) encryptedPlaceholder
    else cipStripMarkerOracle(stripped)
}

private fun cipFinalPreviewOracle(
    typingPreview: String?,
    draftPreview: String?,
    messagePreview: String,
    scheduledLabel: String?
): String = scheduledLabel ?: typingPreview ?: draftPreview ?: messagePreview

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- ChatListItemPreviewPolicy GEN ----------

private val CIP_BODY_POOL119 = arrayOf(
    "", " ", "hello world", "hi there", "a note",
    "pre<meta>cut", "x<meta>y</meta>z", "  <meta>  ",
    "&#72;&#101;llo", "&#x48;&#x69;", "&amp; &lt;tag&gt;", "normal &nbsp; text",
    "&#55296;X", "&#xD800;Y", "&#1114112;", "&#0;", "&#38;amp;", "&#x26;amp;",
    "file:///sdcard/a.jpg", "content://media/1", "maodou-attachment://abc",
    "https://example.com/a/b", "https://x.com/api/attachments/1",
    "www.example.com", "www.x.com/api/attachments/9",
    "https://a.com/ciphertext?q=1", "https://a.com/senderdeviceid=2",
    "https://a.com/share?id=1",
    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"xx\"}]}",
    "{\"algorithm\":\"x\",\"ciphertext\":\"y\"} signal-",
    "signal- {\"algorithm\":\"x\"}",
    "无法解密", "[加密消息]", "[unable to decrypt message]",
    "decrypt failed", "SESSION MISSING", "  decrypt failed  ",
    "[contactUser:u1] hello", "hi [contactUser:u2]", "a\n[contactUser:u3]",
    "  spaced  ", "😀", "üser"
)

private val CIP_LABEL_POOL119 = arrayOf(
    "[图片]", "[视频]", "[语音]", "[文件]", "[位置]", "[贴纸]"
)

private fun genCipBody119(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly || rnd.nextInt(3) == 0) CIP_BODY_POOL119[rnd.nextInt(CIP_BODY_POOL119.size)]
    else if (rnd.nextInt(3) == 0) genTextOf(rnd, rnd.nextInt(0, 120))
    else randomString(rnd, 40)

private fun genCipLabel119(rnd: Random, poolOnly: Boolean): String? =
    if (rnd.nextInt(3) == 0) null
    else if (poolOnly) CIP_LABEL_POOL119[rnd.nextInt(CIP_LABEL_POOL119.size)]
    else randomToken(rnd)

private fun genCipPreview119(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly || rnd.nextInt(3) == 0) CIP_BODY_POOL119[rnd.nextInt(CIP_BODY_POOL119.size)]
    else randomString(rnd, 24)

private fun genCipNullablePreview119(rnd: Random, poolOnly: Boolean): String? =
    if (rnd.nextInt(4) == 0) null else genCipPreview119(rnd, poolOnly)

    @Test
    fun chatListItemPreviewPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val locked = rnd.nextBoolean()
            val lockedPreview = genCipPreview119(rnd, poolOnly = false)
            val secretBlocked = rnd.nextBoolean()
            val secretPreview = genCipPreview119(rnd, poolOnly = false)
            val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
            val lastMessage = genCipBody119(rnd, poolOnly = false)
            val nudgeLabel = genCipPreview119(rnd, poolOnly = false)
            val mediaLabel = genCipLabel119(rnd, poolOnly = false)
            val placeholder = genCipPreview119(rnd, poolOnly = false)
            assertEquals(
                cipMsgPreviewOracle(locked, lockedPreview, secretBlocked, secretPreview, type, lastMessage, nudgeLabel, mediaLabel, placeholder),
                ChatListItemPreviewPolicy.resolveMessagePreview(locked, lockedPreview, secretBlocked, secretPreview, type, lastMessage, nudgeLabel, mediaLabel, placeholder)
            )
            val typing = genCipNullablePreview119(rnd, poolOnly = false)
            val draft = genCipNullablePreview119(rnd, poolOnly = false)
            val message = genCipPreview119(rnd, poolOnly = false)
            val scheduled = genCipNullablePreview119(rnd, poolOnly = false)
            assertEquals(
                cipFinalPreviewOracle(typing, draft, message, scheduled),
                ChatListItemPreviewPolicy.resolveFinalPreview(typing, draft, message, scheduled)
            )
        }
    }

    @Test
    fun chatListItemPreviewPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val locked = rnd.nextBoolean()
            val lockedPreview = genCipPreview119(rnd, poolOnly = true)
            val secretBlocked = rnd.nextBoolean()
            val secretPreview = genCipPreview119(rnd, poolOnly = true)
            val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
            val lastMessage = genCipBody119(rnd, poolOnly = true)
            val nudgeLabel = genCipPreview119(rnd, poolOnly = true)
            val mediaLabel = genCipLabel119(rnd, poolOnly = true)
            val placeholder = genCipPreview119(rnd, poolOnly = true)
            assertEquals(
                cipMsgPreviewOracle(locked, lockedPreview, secretBlocked, secretPreview, type, lastMessage, nudgeLabel, mediaLabel, placeholder),
                ChatListItemPreviewPolicy.resolveMessagePreview(locked, lockedPreview, secretBlocked, secretPreview, type, lastMessage, nudgeLabel, mediaLabel, placeholder)
            )
            val typing = genCipNullablePreview119(rnd, poolOnly = true)
            val draft = genCipNullablePreview119(rnd, poolOnly = true)
            val message = genCipPreview119(rnd, poolOnly = true)
            val scheduled = genCipNullablePreview119(rnd, poolOnly = true)
            assertEquals(
                cipFinalPreviewOracle(typing, draft, message, scheduled),
                ChatListItemPreviewPolicy.resolveFinalPreview(typing, draft, message, scheduled)
            )
        }
    }

    @Test
    fun chatListItemPreviewPolicyPinned() {
        val ph = "[加密消息]"
        // Lock beats secret beats nudge beats media beats visible text.
        assertEquals(
            "locked",
            ChatListItemPreviewPolicy.resolveMessagePreview(true, "locked", true, "secret", MessageType.NUDGE, "hi", "nudge", "[图片]", ph)
        )
        assertEquals(
            "secret",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "locked", true, "secret", MessageType.NUDGE, "hi", "nudge", "[图片]", ph)
        )
        assertEquals(
            "nudge",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "locked", false, "secret", MessageType.NUDGE, "hi", "nudge", "[图片]", ph)
        )
        assertEquals(
            "[图片]",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "locked", false, "secret", MessageType.IMAGE, "hi", "nudge", "[图片]", ph)
        )
        assertEquals(
            "hi",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "locked", false, "secret", MessageType.TEXT, "hi", "nudge", null, ph)
        )
        // Meta tag cut: last "<meta>" wins, text before it survives.
        assertEquals(
            "pre",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "pre<meta>cut", "", null, ph)
        )
        assertEquals(
            "x",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "x<meta>y</meta>z", "", null, ph)
        )
        // HTML entities unescaped in order (decimal, hex, named).
        assertEquals(
            "Hello",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "&#72;&#101;llo", "", null, ph)
        )
        assertEquals(
            "Hi",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "&#x48;&#x69;", "", null, ph)
        )
        assertEquals(
            "& <tag>",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "&amp; &lt;tag&gt;", "", null, ph)
        )
        // Garbage-looking bodies collapse to the placeholder.
        assertEquals(
            ph,
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "无法解密", "", null, ph)
        )
        assertEquals(
            ph,
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "file:///sdcard/a.jpg", "", null, ph)
        )
        assertEquals(
            ph,
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "https://x.com/api/attachments/1", "", null, ph)
        )
        // An ordinary user https link stays visible.
        assertEquals(
            "https://example.com/a/b",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "https://example.com/a/b", "", null, ph)
        )
        // Contact-card markers are stripped from the visible text.
        assertEquals(
            " hello",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "[contactUser:u1] hello", "", null, ph)
        )
        assertEquals(
            "hi ",
            ChatListItemPreviewPolicy.resolveMessagePreview(false, "", false, "", MessageType.TEXT, "hi [contactUser:u2]", "", null, ph)
        )
        // Oracle agrees on the pinned literals.
        assertEquals(
            "pre",
            cipMsgPreviewOracle(false, "", false, "", MessageType.TEXT, "pre<meta>cut", "", null, ph)
        )
        assertEquals(
            ph,
            cipMsgPreviewOracle(false, "", false, "", MessageType.TEXT, "无法解密", "", null, ph)
        )
        // Final preview priority: scheduled > typing > draft > message.
        assertEquals(
            "sched",
            ChatListItemPreviewPolicy.resolveFinalPreview("typing", "draft", "msg", "sched")
        )
        assertEquals(
            "typing",
            ChatListItemPreviewPolicy.resolveFinalPreview("typing", "draft", "msg", null)
        )
        assertEquals(
            "draft",
            ChatListItemPreviewPolicy.resolveFinalPreview(null, "draft", "msg", null)
        )
        assertEquals(
            "msg",
            ChatListItemPreviewPolicy.resolveFinalPreview(null, null, "msg", null)
        )
        // Blank strings still win (only null falls through).
        assertEquals(
            "",
            ChatListItemPreviewPolicy.resolveFinalPreview("", "draft", "msg", null)
        )
        assertEquals(
            "typing",
            cipFinalPreviewOracle("typing", "draft", "msg", null)
        )
    }
}
