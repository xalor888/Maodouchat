// Fuzz / random-oracle tests for ChatListRealtimeProjection (checklist line 931,
// 118th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. zeroChatUnread / zeroChatsUnread / applyPresenceProjection / buildAdminBroadcastProjection.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.Preset
import com.maodouchat.core.model.ResolvedUserVisibility
import com.maodouchat.core.model.User

class ChatListRealtimeProjectionFuzzTest {

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

// ---------- block-118 common codec helpers ----------

// Local hex encoder (same codec as Parse118.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse118.kt, so the codec lives here.
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

// Java String.hashCode over UTF-16 units (31*h + unit, Int wrap).
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// Id-set spec (shared by the CLP and CDL clusters): ids joined by
// ";" with each id hex-encoded; empty set is "".
private fun idSetSpecOf(set: Set<String>): String =
    set.joinToString(";") { encSpecStr(it) }

private fun idSetOfSpec(spec: String): Set<String> =
    if (spec.isEmpty()) emptySet()
    else spec.split(";").map { decSpecStr(it) }.toSet()

// ---------- ChatListRealtimeProjection oracle ----------

private fun clpVisibilityOracle(
    currentIsOnline: Boolean,
    currentStatus: String,
    currentLastSeen: Long,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean
): ResolvedUserVisibility = ResolvedUserVisibility(
    isOnline = when {
        onlineRevoked -> false
        statusRevoked -> currentIsOnline
        else -> eventIsOnline
    },
    status = if (statusRevoked) "" else currentStatus,
    lastSeen = when {
        onlineRevoked -> 0L
        statusRevoked -> currentLastSeen
        else -> eventLastSeen
    }
)

private fun clpZeroChatsOracle(chats: List<Chat>, chatIds: Set<String>): List<Chat> {
    if (chatIds.isEmpty()) return chats
    return chats.map { chat ->
        if (chat.id in chatIds) chat.copy(unreadCount = 0, markedUnread = false) else chat
    }
}

private fun clpZeroChatOracle(chats: List<Chat>, chatId: String): List<Chat> {
    if (isBlankOracle(chatId)) return chats
    return clpZeroChatsOracle(chats, setOf(chatId))
}

private fun clpPresenceOracle(
    chats: List<Chat>,
    userId: String,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean
): List<Chat> = chats.map { chat ->
    chat.copy(
        participants = chat.participants.map { p ->
            if (p.id != userId) p
            else {
                val visibility = clpVisibilityOracle(
                    currentIsOnline = p.isOnline,
                    currentStatus = p.status,
                    currentLastSeen = p.lastSeen,
                    eventIsOnline = eventIsOnline,
                    eventLastSeen = eventLastSeen,
                    onlineRevoked = onlineRevoked,
                    statusRevoked = statusRevoked
                )
                p.copy(
                    isOnline = visibility.isOnline,
                    status = visibility.status,
                    lastSeen = visibility.lastSeen
                )
            }
        }
    )
}

// Encoded projection (createdAt/updatedAt excluded: they default to
// now and are nondeterministic). Extra travels as hex key=value
// pairs, same as clpAdminBcEncode below.
private fun clpAdminBcOracle(
    title: String,
    body: String,
    timestamp: Long,
    senderLabel: String
): String? {
    val trimmed = trimOracle(body)
    if (isBlankOracle(trimmed)) return null
    val id = "admin_bc_" + timestamp.toString() + "_" + hashCodeOracle(trimmed).toString()
    val mergeKey = "admin_broadcast_" + timestamp.toString()
    val preview = takeOracle(trimmed, 200)
    val banner = takeOracle(title + ": " + trimmed, 240)
    return encSpecStr(id) + "|" + encSpecStr("SECURITY") + "|" + encSpecStr(mergeKey) + "|" +
        encSpecStr(title) + "|" + encNullableSpecStr(senderLabel) + "|" +
        encNullableSpecStr(preview) + "|N|" +
        encSpecStr("kind") + "=" + encSpecStr("admin_broadcast") + "|" + encSpecStr(banner)
}

// Encoder for a production AdminBroadcastProjection? (same shape as
// the oracle output; createdAt/updatedAt excluded: nondeterministic).
private fun clpAdminBcEncode(proj: AdminBroadcastProjection?): String? {
    if (proj == null) return null
    val item = proj.item
    return encSpecStr(item.id) + "|" + encSpecStr(item.type) + "|" + encSpecStr(item.mergeKey) + "|" +
        encSpecStr(item.title) + "|" + encNullableSpecStr(item.subtitle) + "|" +
        encNullableSpecStr(item.preview) + "|" + encNullableSpecStr(item.deeplink) + "|" +
        item.extra.entries.joinToString(",") { encSpecStr(it.key) + "=" + encSpecStr(it.value) } + "|" +
        encSpecStr(proj.bannerText)
}

private fun clpUserSpecOf(u: User): String =
    encSpecStr(u.id) + "|" + encSpecStr(u.name) + "|" + encNullableSpecStr(u.avatar) + "|" +
        encSpecStr(u.email) + "|" + (if (u.isOnline) "B1" else "B0") + "|" +
        encSpecStr(u.status) + "|" + encNullableSpecStr(u.nickname) + "|" + u.lastSeen.toString()

private fun clpUserOfSpec(spec: String): User {
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

private fun clpChatSpecOf(c: Chat): String =
    encSpecStr(c.id) + "|" +
        c.participants.joinToString(";") { encSpecStr(clpUserSpecOf(it)) } + "|" +
        encSpecStr(c.lastMessage) + "|" + c.lastMessageType.name + "|" +
        c.lastMessageTime.toString() + "|" + c.unreadCount.toString() + "|" +
        (if (c.isGroup) "B1" else "B0") + "|" + encSpecStr(c.chatType) + "|" +
        encNullableSpecStr(c.groupName) + "|" + encNullableSpecStr(c.groupAnnouncement) + "|" +
        encNullableSpecStr(c.groupAvatar) + "|" + c.memberRevision.toString() + "|" +
        c.pinnedAt.toString() + "|" + (if (c.notificationsMuted) "B1" else "B0") + "|" +
        (if (c.archived) "B1" else "B0") + "|" + (if (c.markedUnread) "B1" else "B0") + "|" +
        c.settingsUpdatedAt.toString() + "|" + c.disappearingMessageSeconds.toString()

private fun clpChatOfSpec(spec: String): Chat {
    val f = spec.split("|")
    return Chat(
        id = decSpecStr(f[0]),
        participants = if (f[1].isEmpty()) emptyList()
            else f[1].split(";").map { clpUserOfSpec(decSpecStr(it)) },
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

private fun clpChatListSpecOf(list: List<Chat>): String =
    list.joinToString(";") { encSpecStr(clpChatSpecOf(it)) }

private fun clpChatListOfSpec(spec: String): List<Chat> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { clpChatOfSpec(decSpecStr(it)) }

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- ChatListRealtimeProjection GEN ----------

private val CLP_ID_POOL118 = arrayOf(
    "", "c1", "c2", "a", "b", "üser", " ", "c1", "chat-1", "😀"
)

private val CLP_LONG_POOL118 = longArrayOf(
    0L, 1L, -1L, 1000L, 1700000000000L, 9223372036854775807L, Long.MIN_VALUE
)

private val CLP_INT_POOL118 = intArrayOf(
    0, 1, 2, -1, 2147483647, -2147483648
)

private fun genClpUser118(rnd: Random, poolOnly: Boolean): User {
    val id = if (poolOnly) CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
        else randomToken(rnd)
    val name = if (rnd.nextInt(3) == 0) randomToken(rnd) else randomString(rnd, 12)
    val avatar = if (rnd.nextBoolean()) null else randomToken(rnd)
    val nickname = when (rnd.nextInt(4)) {
        0 -> null
        1 -> ""
        2 -> "  "
        else -> randomToken(rnd)
    }
    return User(
        id = id,
        name = name,
        avatar = avatar,
        email = randomToken(rnd),
        isOnline = rnd.nextBoolean(),
        status = randomToken(rnd),
        nickname = nickname,
        lastSeen = if (poolOnly) CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            else rnd.nextLong()
    )
}

private fun genClpChat118(rnd: Random, poolOnly: Boolean): Chat {
    val nParts = rnd.nextInt(0, 4)
    return Chat(
        id = if (poolOnly) CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            else randomToken(rnd),
        participants = List(nParts) { genClpUser118(rnd, poolOnly) },
        lastMessage = randomString(rnd, 12),
        lastMessageType = MessageType.entries[rnd.nextInt(MessageType.entries.size)],
        lastMessageTime = if (poolOnly) CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            else rnd.nextLong(),
        unreadCount = if (poolOnly) CLP_INT_POOL118[rnd.nextInt(CLP_INT_POOL118.size)]
            else rnd.nextInt(),
        isGroup = rnd.nextBoolean(),
        chatType = arrayOf("DIRECT", "GROUP", "CHANNEL", "SECRET", "", "X")[rnd.nextInt(6)],
        groupName = if (rnd.nextBoolean()) null else randomToken(rnd),
        groupAnnouncement = if (rnd.nextBoolean()) null else randomToken(rnd),
        groupAvatar = if (rnd.nextBoolean()) null else randomToken(rnd),
        memberRevision = if (poolOnly) CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            else rnd.nextLong(),
        pinnedAt = if (poolOnly) CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            else rnd.nextLong(),
        notificationsMuted = rnd.nextBoolean(),
        archived = rnd.nextBoolean(),
        markedUnread = rnd.nextBoolean(),
        settingsUpdatedAt = if (poolOnly) CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            else rnd.nextLong(),
        disappearingMessageSeconds = if (poolOnly) CLP_INT_POOL118[rnd.nextInt(CLP_INT_POOL118.size)]
            else rnd.nextInt()
    )
}

private fun genClpChatList118(rnd: Random, poolOnly: Boolean): List<Chat> {
    val n = rnd.nextInt(0, 4)
    return List(n) { genClpChat118(rnd, poolOnly) }
}

private fun genClpIdSet118(rnd: Random, poolOnly: Boolean): Set<String> {
    val n = rnd.nextInt(0, 4)
    val out = LinkedHashSet<String>()
    repeat(n) {
        out.add(
            if (poolOnly) CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            else randomToken(rnd)
        )
    }
    return out
}

    @Test
    fun chatListRealtimeProjectionRandom() {
        val rnd = Random(42)
        repeat(300) {
            val chats = genClpChatList118(rnd, poolOnly = false)
            val chatId = randomToken(rnd)
            assertEquals(clpZeroChatOracle(chats, chatId), zeroChatUnread(chats, chatId))
            val ids = genClpIdSet118(rnd, poolOnly = false)
            assertEquals(clpZeroChatsOracle(chats, ids), zeroChatsUnread(chats, ids))
            val userId = randomToken(rnd)
            val eventOnline = rnd.nextBoolean()
            val eventSeen = rnd.nextLong()
            val onlineRevoked = rnd.nextBoolean()
            val statusRevoked = rnd.nextBoolean()
            assertEquals(
                clpPresenceOracle(chats, userId, eventOnline, eventSeen, onlineRevoked, statusRevoked),
                applyPresenceProjection(chats, userId, eventOnline, eventSeen, onlineRevoked, statusRevoked)
            )
            val title = randomToken(rnd)
            val body = randomString(rnd, 60)
            val ts = rnd.nextLong()
            val sender = randomToken(rnd)
            assertEquals(clpAdminBcOracle(title, body, ts, sender), clpAdminBcEncode(buildAdminBroadcastProjection(title, body, ts, sender)))
        }
    }

    @Test
    fun chatListRealtimeProjectionEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val chats = genClpChatList118(rnd, poolOnly = true)
            val chatId = CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            assertEquals(clpZeroChatOracle(chats, chatId), zeroChatUnread(chats, chatId))
            val ids = genClpIdSet118(rnd, poolOnly = true)
            assertEquals(clpZeroChatsOracle(chats, ids), zeroChatsUnread(chats, ids))
            val userId = CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            val eventOnline = rnd.nextBoolean()
            val eventSeen = CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            val onlineRevoked = rnd.nextBoolean()
            val statusRevoked = rnd.nextBoolean()
            assertEquals(
                clpPresenceOracle(chats, userId, eventOnline, eventSeen, onlineRevoked, statusRevoked),
                applyPresenceProjection(chats, userId, eventOnline, eventSeen, onlineRevoked, statusRevoked)
            )
            val title = CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            val body = if (rnd.nextBoolean()) "" else genTextOf(rnd, rnd.nextInt(0, 500))
            val ts = CLP_LONG_POOL118[rnd.nextInt(CLP_LONG_POOL118.size)]
            val sender = CLP_ID_POOL118[rnd.nextInt(CLP_ID_POOL118.size)]
            assertEquals(clpAdminBcOracle(title, body, ts, sender), clpAdminBcEncode(buildAdminBroadcastProjection(title, body, ts, sender)))
        }
    }

    @Test
    fun chatListRealtimeProjectionPinned() {
        val chatA = Chat(id = "c1", unreadCount = 5, markedUnread = true)
        val chatB = Chat(id = "c2", unreadCount = 0, markedUnread = false)
        val chats = listOf(chatA, chatB)
        // Blank chatId -> unchanged.
        assertEquals(chats, zeroChatUnread(chats, "   "))
        assertEquals(chats, clpZeroChatOracle(chats, "   "))
        // Matching id zeroes unreadCount and clears markedUnread.
        assertEquals(
            listOf(chatA.copy(unreadCount = 0, markedUnread = false), chatB),
            zeroChatUnread(chats, "c1")
        )
        // Empty id set -> unchanged; unknown id -> unchanged.
        assertEquals(chats, zeroChatsUnread(chats, emptySet()))
        assertEquals(chats, zeroChatsUnread(chats, setOf("zz")))
        assertEquals(
            listOf(chatA.copy(unreadCount = 0, markedUnread = false), chatB.copy(unreadCount = 0, markedUnread = false)),
            zeroChatsUnread(chats, setOf("c1", "c2"))
        )
        // Presence: only the matching participant moves; revoked arms win.
        val me = User(id = "u1", name = "Me", isOnline = false, status = "busy", lastSeen = 111L)
        val other = User(id = "u2", name = "Other", isOnline = true, status = "hi", lastSeen = 222L)
        val pchats = listOf(Chat(id = "c1", participants = listOf(me, other)))
        val moved = applyPresenceProjection(pchats, "u1", true, 999L, false, false)
        assertEquals(true, moved[0].participants[0].isOnline)
        assertEquals("busy", moved[0].participants[0].status)
        assertEquals(999L, moved[0].participants[0].lastSeen)
        assertEquals(other, moved[0].participants[1])
        val revoked = applyPresenceProjection(pchats, "u1", true, 999L, true, false)
        assertEquals(false, revoked[0].participants[0].isOnline)
        assertEquals(0L, revoked[0].participants[0].lastSeen)
        assertEquals("busy", revoked[0].participants[0].status)
        val statusRevoked = applyPresenceProjection(pchats, "u1", true, 999L, false, true)
        assertEquals(false, statusRevoked[0].participants[0].isOnline)
        assertEquals("", statusRevoked[0].participants[0].status)
        assertEquals(111L, statusRevoked[0].participants[0].lastSeen)
        // Admin broadcast: blank body -> null; hashCode pins the id.
        assertEquals(null, buildAdminBroadcastProjection("T", "   ", 1000L, "S"))
        assertEquals(null, clpAdminBcOracle("T", "   ", 1000L, "S"))
        val proj = buildAdminBroadcastProjection("Title", "hello", 1000L, "Sender")
        assertTrue(proj != null)
        assertEquals("admin_bc_1000_" + "hello".hashCode().toString(), proj!!.item.id)
        assertEquals("SECURITY", proj.item.type)
        assertEquals("admin_broadcast_1000", proj.item.mergeKey)
        assertEquals("Title", proj.item.title)
        assertEquals("Sender", proj.item.subtitle)
        assertEquals("hello", proj.item.preview)
        assertEquals(null, proj.item.deeplink)
        assertEquals(mapOf("kind" to "admin_broadcast"), proj.item.extra)
        assertEquals("Title: hello", proj.bannerText)
        assertEquals(clpAdminBcOracle("Title", "hello", 1000L, "Sender"), clpAdminBcEncode(proj))
        // Oracle agrees on the pinned literals.
        assertEquals(
            listOf(chatA.copy(unreadCount = 0, markedUnread = false), chatB),
            clpZeroChatOracle(chats, "c1")
        )
    }
}
