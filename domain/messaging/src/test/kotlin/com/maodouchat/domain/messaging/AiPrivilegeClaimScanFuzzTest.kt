// Fuzz / random-oracle tests for AiPrivilegeClaimScan.scanPrivilegeClaims / annotateIfPrivilegedHallucination (checklist line 931,
// 121st block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. scanPrivilegeClaims / annotateIfPrivilegedHallucination: regex claim classification + disclaimer annotation.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import com.maodouchat.domain.messaging.AiContextMessage
import com.maodouchat.domain.messaging.AiContextSenders
import com.maodouchat.domain.messaging.LocalFileMetadata
import com.maodouchat.domain.messaging.PrivilegeClaimKind
import com.maodouchat.domain.messaging.PrivilegeScan

class AiPrivilegeClaimScanFuzzTest {

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

// ---------- block-121 common codec helpers ----------

// Local hex encoder (same codec as Parse121.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse121.kt, so the codec lives here.
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

// MessageMeta spec (ACM cluster): only the three fields the block's
// functions read -- "aiAssisted|voiceTranscript|translations"
// (aiAssisted B1/B0, voiceTranscript nullable hex, translations
// ";"-joined "hex=hex" pairs in insertion order; "" = empty).
private fun metaSpecOf(m: MessageMeta): String =
    (if (m.aiAssisted) "B1" else "B0") + "|" + encNullableSpecStr(m.voiceTranscript) + "|" +
        m.translations.entries.joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value) }

private fun metaOfSpec(spec: String): MessageMeta {
    val f = spec.split("|")
    return MessageMeta(
        aiAssisted = f[0] == "B1",
        voiceTranscript = decNullableSpecStr(f[1]),
        translations = if (f[2].isEmpty()) emptyMap()
            else f[2].split(";").associate {
                val kv = it.split("=")
                decSpecStr(kv[0]) to decSpecStr(kv[1])
            }
    )
}

// Message spec (ACM cluster): 9 fields; the remaining Message fields
// keep their defaults on both sides and copy() preserves them.
// "id|chatId|senderId|content|type|timestamp|status|editedAt|meta"
// (editedAt is N | S+decimal; meta is encSpecStr(metaSpec)).
private fun msgSpecOf(m: Message): String =
    encSpecStr(m.id) + "|" + encSpecStr(m.chatId) + "|" + encSpecStr(m.senderId) + "|" +
        encSpecStr(m.content) + "|" + m.type.name + "|" + m.timestamp.toString() + "|" +
        m.status.name + "|" + (if (m.editedAt == null) "N" else "S" + m.editedAt.toString()) + "|" +
        encSpecStr(metaSpecOf(m.meta))

private fun msgOfSpec(spec: String): Message {
    val f = spec.split("|")
    return Message(
        id = decSpecStr(f[0]),
        chatId = decSpecStr(f[1]),
        senderId = decSpecStr(f[2]),
        content = decSpecStr(f[3]),
        type = MessageType.valueOf(f[4]),
        timestamp = f[5].toLong(),
        status = MessageStatus.valueOf(f[6]),
        editedAt = if (f[7] == "N") null else f[7].substring(1).toLong(),
        meta = if (f.size > 8) metaOfSpec(decSpecStr(f[8])) else MessageMeta()
    )
}

private fun msgListSpecOf(list: List<Message>): String =
    list.joinToString(";") { encSpecStr(msgSpecOf(it)) }

private fun msgListOfSpec(spec: String): List<Message> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { msgOfSpec(decSpecStr(it)) }

// AiContextSenders spec (ACM cluster):
// "currentUserId|currentUserLabel|fallbackLabel|namesByUserId"
// (names ";"-joined "hex=hex" pairs in insertion order).
private fun sendersSpecOf(s: AiContextSenders): String =
    encSpecStr(s.currentUserId) + "|" + encSpecStr(s.currentUserLabel) + "|" +
        encSpecStr(s.fallbackLabel) + "|" +
        s.namesByUserId.entries.joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value) }

private fun sendersOfSpec(spec: String): AiContextSenders {
    val f = spec.split("|")
    return AiContextSenders(
        currentUserId = decSpecStr(f[0]),
        currentUserLabel = decSpecStr(f[1]),
        fallbackLabel = decSpecStr(f[2]),
        namesByUserId = if (f[3].isEmpty()) emptyMap()
            else f[3].split(";").associate {
                val kv = it.split("=")
                decSpecStr(kv[0]) to decSpecStr(kv[1])
            }
    )
}

// AiContextMessage spec (ACM cluster): "sender|text".
private fun ctxSpecOf(c: AiContextMessage): String =
    encSpecStr(c.sender) + "|" + encSpecStr(c.text)

private fun ctxOfSpec(spec: String): AiContextMessage {
    val f = spec.split("|")
    return AiContextMessage(sender = decSpecStr(f[0]), text = decSpecStr(f[1]))
}

private fun ctxListSpecOf(list: List<AiContextMessage>): String =
    list.joinToString(";") { encSpecStr(ctxSpecOf(it)) }

private fun ctxListOfSpec(spec: String): List<AiContextMessage> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { ctxOfSpec(decSpecStr(it)) }

// PrivilegeScan spec (APC cluster): "B1/B0|KIND;KIND" ("" = no kinds).
private fun scanSpecOf(s: PrivilegeScan): String =
    (if (s.hasClaim) "B1" else "B0") + "|" + s.kinds.joinToString(";") { it.name }

private fun scanOfSpec(spec: String): PrivilegeScan {
    val f = spec.split("|")
    return PrivilegeScan(
        hasClaim = f[0] == "B1",
        kinds = if (f[1].isEmpty()) emptySet()
            else f[1].split(";").map { PrivilegeClaimKind.valueOf(it) }.toSet()
    )
}

// LocalFileMetadata spec (AIP cluster): "fileName|mimeType|sizeBytes".
private fun localMetaSpecOf(m: LocalFileMetadata): String =
    encSpecStr(m.fileName) + "|" + encSpecStr(m.mimeType) + "|" + m.sizeBytes.toString()

private fun localMetaOfSpec(spec: String): LocalFileMetadata {
    val f = spec.split("|")
    return LocalFileMetadata(
        fileName = decSpecStr(f[0]),
        mimeType = decSpecStr(f[1]),
        sizeBytes = f[2].toLong()
    )
}

// User spec (CLU cluster): "id|name|avatar|email|isOnline|status|
// nickname|lastSeen" (nickname/avatar nullable).
private fun userSpecOf(u: User): String =
    encSpecStr(u.id) + "|" + encSpecStr(u.name) + "|" + encNullableSpecStr(u.avatar) + "|" +
        encSpecStr(u.email) + "|" + (if (u.isOnline) "B1" else "B0") + "|" +
        encSpecStr(u.status) + "|" + encNullableSpecStr(u.nickname) + "|" + u.lastSeen.toString()

private fun userOfSpec(spec: String): User {
    val f = spec.split("|")
    return User(
        id = decSpecStr(f[0]),
        name = decSpecStr(f[1]),
        avatar = decNullableSpecStr(f[2]),
        email = decSpecStr(f[3]),
        isOnline = f[4] == "B1",
        status = decSpecStr(f[5]),
        nickname = decNullableSpecStr(f[6]),
        lastSeen = f[7].toLong()
    )
}

// Chat spec (CLP + CLU clusters): "id|participants|lastMessage|
// lastMessageType|lastMessageTime|unreadCount|isGroup|chatType|
// groupName|groupAnnouncement|groupAvatar|memberRevision|pinnedAt|
// notificationsMuted|archived|markedUnread|settingsUpdatedAt|
// disappearingMessageSeconds" (participants = userSpecs joined by
// ";" each encSpecStr'd).
private fun chatSpecOf(c: Chat): String =
    encSpecStr(c.id) + "|" +
        c.participants.joinToString(";") { encSpecStr(userSpecOf(it)) } + "|" +
        encSpecStr(c.lastMessage) + "|" + c.lastMessageType.name + "|" +
        c.lastMessageTime.toString() + "|" + c.unreadCount.toString() + "|" +
        (if (c.isGroup) "B1" else "B0") + "|" + encSpecStr(c.chatType) + "|" +
        encNullableSpecStr(c.groupName) + "|" + encNullableSpecStr(c.groupAnnouncement) + "|" +
        encNullableSpecStr(c.groupAvatar) + "|" + c.memberRevision.toString() + "|" +
        c.pinnedAt.toString() + "|" + (if (c.notificationsMuted) "B1" else "B0") + "|" +
        (if (c.archived) "B1" else "B0") + "|" + (if (c.markedUnread) "B1" else "B0") + "|" +
        c.settingsUpdatedAt.toString() + "|" + c.disappearingMessageSeconds.toString()

private fun chatOfSpec(spec: String): Chat {
    val f = spec.split("|")
    return Chat(
        id = decSpecStr(f[0]),
        participants = if (f[1].isEmpty()) emptyList()
            else f[1].split(";").map { userOfSpec(decSpecStr(it)) },
        lastMessage = decSpecStr(f[2]),
        lastMessageType = MessageType.valueOf(f[3]),
        lastMessageTime = f[4].toLong(),
        unreadCount = f[5].toInt(),
        isGroup = f[6] == "B1",
        chatType = decSpecStr(f[7]),
        groupName = decNullableSpecStr(f[8]),
        groupAnnouncement = decNullableSpecStr(f[9]),
        groupAvatar = decNullableSpecStr(f[10]),
        memberRevision = f[11].toLong(),
        pinnedAt = f[12].toLong(),
        notificationsMuted = f[13] == "B1",
        archived = f[14] == "B1",
        markedUnread = f[15] == "B1",
        settingsUpdatedAt = f[16].toLong(),
        disappearingMessageSeconds = f[17].toInt()
    )
}

private fun chatListSpecOf(list: List<Chat>): String =
    list.joinToString(";") { encSpecStr(chatSpecOf(it)) }

private fun chatListOfSpec(spec: String): List<Chat> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { chatOfSpec(decSpecStr(it)) }

// ---------- AiPrivilegeClaimScan oracle ----------

private val APC_CONTROL_CHARS_ORACLE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

private val APC_TRANSFER_ORACLE = Regex(
    "(?i)已(经)?(完成|执行|发起|确认)?\\s*(转账|打款|付款|支付|汇款)|" +
        "(transferred|sent\\s+payment|wired\\s+funds|payment\\s+completed)|" +
        "(帮你|为你|替你).{0,12}(转账|打款|付款)|" +
        "(i\\s+have\\s+)?(transferred|sent)\\s+(the\\s+)?(money|funds|payment)"
)

private val APC_ACCOUNT_ORACLE = Regex(
    "(?i)已(经)?(注销|删除账号|删号|清空账号)|" +
        "(account\\s+(has\\s+been\\s+)?(deleted|closed|wiped))|" +
        "(帮你|为你).{0,12}(注销|删号)"
)

private val APC_AUTH_ORACLE = Regex(
    "(?i)已(经)?(重置密码|修改密码|轮换密钥|导出密钥)|" +
        "(password\\s+(has\\s+been\\s+)?(reset|changed)|keys?\\s+(exported|rotated))|" +
        "(帮你|为你).{0,12}(改密|重置密码|导出密钥)"
)

private val APC_ADMIN_ORACLE = Regex(
    "(?i)已(经)?(封禁|禁言|踢出|转让群主|设为管理员)|" +
        "(banned|muted|kicked|ownership\\s+transferred|promoted\\s+to\\s+admin)|" +
        "(帮你|为你).{0,12}(封禁|禁言|踢人|转让群主)"
)

private fun apcScanOracle(output: String?): PrivilegeScan {
    val body = takeOracle(APC_CONTROL_CHARS_ORACLE.replace(output.orEmpty(), ""), 12_000)
    if (isBlankOracle(body)) return PrivilegeScan(false)
    val kinds = buildSet {
        if (APC_TRANSFER_ORACLE.containsMatchIn(body)) add(PrivilegeClaimKind.TRANSFER_OR_PAYMENT)
        if (APC_ACCOUNT_ORACLE.containsMatchIn(body)) add(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE)
        if (APC_AUTH_ORACLE.containsMatchIn(body)) add(PrivilegeClaimKind.AUTH_OR_KEY)
        if (APC_ADMIN_ORACLE.containsMatchIn(body)) add(PrivilegeClaimKind.ADMIN_OR_MODERATION)
    }
    return PrivilegeScan(hasClaim = kinds.isNotEmpty(), kinds = kinds)
}

private fun apcAnnotateOracle(output: String?, disclaimer: String): String {
    val text = trimOracle(output.orEmpty())
    if (text.isEmpty()) return ""
    val scan = apcScanOracle(text)
    if (!scan.hasClaim) return text
    val trimmedNote = trimOracle(disclaimer)
    val note = if (isBlankOracle(trimmedNote)) {
        "AI cannot execute privileged actions; verify before trusting claims."
    } else {
        trimmedNote
    }
    return "$text\n\n$note"
}

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- AiPrivilegeClaimScan GEN ----------

private val APC_OUTPUT_POOL121 = arrayOf(
    "", " ", "   ", "\u00A0", "hello", "明天开会讨论系统升级", "今天天气不错",
    "已经完成转账", "已经转账", "已经执行付款", "已经发起打款", "已经确认支付",
    "已经完成汇款", "transferred the money", "I have transferred the funds",
    "sent payment", "payment completed", "wired funds", "帮你转账", "为你打款",
    "替你付款", "帮你转了一笔账", "已经注销", "已经删除账号", "已经删号",
    "account has been deleted", "account closed", "帮你注销", "已经重置密码",
    "已经修改密码", "已经轮换密钥", "password has been reset", "keys exported",
    "帮你改密", "已经封禁", "已经禁言", "已经踢出", "banned", "muted", "kicked",
    "promoted to admin", "帮你封禁", "已经完成转账 and 已经注销",
    "转账", "付款", "已经完成", "transferred", "money",
    "已经完成转账\u0000\u0007", "\u0000已经注销\u001F", "x".repeat(13000),
    "已经完成转账" + "y".repeat(13000)
)

private val APC_DISCLAIMER_POOL121 = arrayOf(
    "", " ", "请核实", "  verify  ", "AI cannot execute privileged actions; verify before trusting claims.", "😀"
)

private fun genApcOutput121(rnd: Random, poolOnly: Boolean): String? =
    when (rnd.nextInt(6)) {
        0 -> null
        else -> if (poolOnly) APC_OUTPUT_POOL121[rnd.nextInt(APC_OUTPUT_POOL121.size)]
            else randomString(rnd, 12)
    }

private fun genApcDisclaimer121(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly) APC_DISCLAIMER_POOL121[rnd.nextInt(APC_DISCLAIMER_POOL121.size)]
    else randomString(rnd, 12)

    @Test
    fun aiPrivilegeClaimScanRandom() {
        val rnd = Random(42)
        repeat(300) {
            val output = genApcOutput121(rnd, poolOnly = false)
            val disclaimer = genApcDisclaimer121(rnd, poolOnly = false)
            assertEquals(
                apcScanOracle(output),
                AiPrivilegeClaimScan.scanPrivilegeClaims(output)
            )
            assertEquals(
                apcAnnotateOracle(output, disclaimer),
                AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(output, disclaimer)
            )
        }
    }

    @Test
    fun aiPrivilegeClaimScanEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val output = genApcOutput121(rnd, poolOnly = true)
            val disclaimer = genApcDisclaimer121(rnd, poolOnly = true)
            assertEquals(
                apcScanOracle(output),
                AiPrivilegeClaimScan.scanPrivilegeClaims(output)
            )
            assertEquals(
                apcAnnotateOracle(output, disclaimer),
                AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(output, disclaimer)
            )
        }
    }

    @Test
    fun aiPrivilegeClaimScanPinned() {
        fun scan(s: String?) = AiPrivilegeClaimScan.scanPrivilegeClaims(s)
        // Each kind on its own.
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT),
            scan("已经完成转账").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE),
            scan("已经注销").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.AUTH_OR_KEY),
            scan("已经重置密码").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ADMIN_OR_MODERATION),
            scan("已经封禁").kinds
        )
        // English arms.
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT),
            scan("I have transferred the funds").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE),
            scan("account has been deleted").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.AUTH_OR_KEY),
            scan("keys exported").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ADMIN_OR_MODERATION),
            scan("promoted to admin").kinds
        )
        // Multiple kinds in one output, in fixed order.
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT, PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE),
            scan("已经完成转账 and 已经注销").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT, PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE),
            apcScanOracle("已经完成转账 and 已经注销").kinds
        )
        // Blank and ordinary text have no claim.
        assertEquals(false, scan(null).hasClaim)
        assertEquals(false, scan("   ").hasClaim)
        assertEquals(false, scan("明天开会讨论系统升级").hasClaim)
        assertEquals(false, scan("转账").hasClaim)
        assertEquals(false, scan("已经完成").hasClaim)
        // Control chars are stripped before matching.
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT),
            scan("已经完成转账\u0000\u0007").kinds
        )
        // The 12,000-unit take: a claim that starts at 11,996 fits,
        // one that starts at 11,997 is cut off mid-word.
        assertEquals(
            true,
            scan("y".repeat(11996) + "已经转账").hasClaim
        )
        assertEquals(
            false,
            scan("y".repeat(11997) + "已经转账").hasClaim
        )
        assertEquals(
            true,
            apcScanOracle("y".repeat(11996) + "已经转账").hasClaim
        )
        // Annotate: clean text returns the trimmed text verbatim.
        val clean = "今天天气不错"
        assertEquals(clean, AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(clean, "请核实"))
        assertEquals(clean, apcAnnotateOracle(clean, "请核实"))
        assertEquals("今天天气不错", AiPrivilegeClaimScan.annotateIfPrivilegedHallucination("  今天天气不错  ", "请核实"))
        // Annotate: a claim appends the disclaimer after a blank line.
        assertEquals(
            "已经完成转账\n\n请核实",
            AiPrivilegeClaimScan.annotateIfPrivilegedHallucination("已经完成转账", "请核实")
        )
        assertEquals(
            "已经完成转账\n\n请核实",
            apcAnnotateOracle("已经完成转账", "请核实")
        )
        // Blank disclaimer falls back to the built-in sentence.
        assertEquals(
            "已经完成转账\n\nAI cannot execute privileged actions; verify before trusting claims.",
            AiPrivilegeClaimScan.annotateIfPrivilegedHallucination("已经完成转账", "   ")
        )
        // Empty input annotates to empty, whatever the disclaimer.
        assertEquals("", AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(null, "请核实"))
        assertEquals("", AiPrivilegeClaimScan.annotateIfPrivilegedHallucination("   ", "请核实"))
        assertEquals("", apcAnnotateOracle(null, "请核实"))
        // The scan spec round-trips.
        assertEquals(
            scan("已经完成转账"),
            scanOfSpec(scanSpecOf(scan("已经完成转账")))
        )
    }
}
