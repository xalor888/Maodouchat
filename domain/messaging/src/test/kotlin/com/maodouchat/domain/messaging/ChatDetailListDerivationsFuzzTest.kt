// Fuzz / random-oracle tests for ChatDetailListDerivations (checklist line 931,
// 118th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. resolveMessagesById / resolveParticipantNamesById / resolveSelectedMessages / isMessageSelectionMode.
package com.maodouchat.domain.messaging

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

class ChatDetailListDerivationsFuzzTest {

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

// ---------- ChatDetailListDerivations oracle ----------

private fun cdlDisplayNameOracle(u: User): String {
    val nick = u.nickname
    if (nick != null && !isBlankOracle(nick)) return nick
    if (!isBlankOracle(u.name)) return u.name
    return u.id
}

private fun cdlResolveMessagesByIdOracle(messages: List<Message>): Map<String, Message> {
    val out = LinkedHashMap<String, Message>()
    for (m in messages) out[m.id] = m
    return out
}

private fun cdlResolveParticipantNamesByIdOracle(
    participants: List<User>?,
    memberNicknameByUser: Map<String, String>,
    chatIsGroup: Boolean
): Map<String, String> {
    val base = LinkedHashMap<String, String>()
    for (p in participants.orEmpty()) base[p.id] = cdlDisplayNameOracle(p)
    if (chatIsGroup && memberNicknameByUser.isNotEmpty()) {
        for ((k, v) in memberNicknameByUser) base[k] = v
    }
    return base
}

private fun cdlResolveSelectedMessagesOracle(
    messages: List<Message>,
    selectedMessageIds: Set<String>
): List<Message> = messages.filter { it.id in selectedMessageIds }

private fun cdlIsMessageSelectionModeOracle(selectedMessageIds: Set<String>): Boolean =
    selectedMessageIds.isNotEmpty()

private fun cdlMessageSpecOf(m: Message): String =
    encSpecStr(m.id) + "|" + encSpecStr(m.chatId) + "|" + encSpecStr(m.senderId) + "|" +
        encSpecStr(m.content) + "|" + m.type.name + "|" + m.timestamp.toString() + "|" + m.status.name

private fun cdlMessageOfSpec(spec: String): Message {
    val f = spec.split("|")
    return Message(
        id = decSpecStr(f[0]),
        chatId = decSpecStr(f[1]),
        senderId = decSpecStr(f[2]),
        content = decSpecStr(f[3]),
        type = MessageType.valueOf(f[4]),
        timestamp = f[5].toLong(),
        status = MessageStatus.valueOf(f[6])
    )
}

private fun cdlMessageListSpecOf(list: List<Message>): String =
    list.joinToString(";") { encSpecStr(cdlMessageSpecOf(it)) }

private fun cdlMessageListOfSpec(spec: String): List<Message> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { cdlMessageOfSpec(decSpecStr(it)) }

private fun cdlUserSpecOf(u: User): String =
    encSpecStr(u.id) + "|" + encSpecStr(u.name) + "|" + encNullableSpecStr(u.avatar) + "|" +
        encSpecStr(u.email) + "|" + (if (u.isOnline) "B1" else "B0") + "|" +
        encSpecStr(u.status) + "|" + encNullableSpecStr(u.nickname) + "|" + u.lastSeen.toString()

private fun cdlUserOfSpec(spec: String): User {
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

private fun cdlUserListSpecOf(list: List<User>): String =
    list.joinToString(";") { encSpecStr(cdlUserSpecOf(it)) }

private fun cdlUserListOfSpec(spec: String): List<User> =
    if (spec.isEmpty()) emptyList()
    else spec.split(";").map { cdlUserOfSpec(decSpecStr(it)) }

private fun cdlStrMapSpecOf(map: Map<String, String>): String =
    map.entries.joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value) }

private fun cdlStrMapOfSpec(spec: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (spec.isEmpty()) return out
    for (pair in spec.split(";")) {
        val eq = pair.indexOf('=')
        out[decSpecStr(pair.substring(0, eq))] = decSpecStr(pair.substring(eq + 1))
    }
    return out
}

// Encoders for oracle-vs-production comparison in the @Test files:
// map outputs travel as ordered id=name pairs; message outputs as
// ordered ids (the winning message's content distinguishes dups in
// the by-id leg).
private fun cdlByIdEncode(map: Map<String, Message>): String =
    map.entries.joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value.content) }

private fun cdlNamesEncode(map: Map<String, String>): String =
    map.entries.joinToString(";") { encSpecStr(it.key) + "=" + encSpecStr(it.value) }

private fun cdlIdsEncode(list: List<Message>): String =
    list.joinToString(";") { encSpecStr(it.id) }

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

// ---------- ChatDetailListDerivations GEN ----------

private val CDL_ID_POOL118 = arrayOf(
    "", "m1", "m2", "a", "b", "üser", " ", "m1", "msg-1", "😀"
)

private val CDL_LONG_POOL118 = longArrayOf(
    0L, 1L, -1L, 1000L, 1700000000000L, 9223372036854775807L, Long.MIN_VALUE
)

private fun genCdlMessage118(rnd: Random, poolOnly: Boolean): Message {
    return Message(
        id = if (poolOnly) CDL_ID_POOL118[rnd.nextInt(CDL_ID_POOL118.size)]
            else randomToken(rnd),
        chatId = randomToken(rnd),
        senderId = randomToken(rnd),
        content = if (rnd.nextInt(3) == 0) randomToken(rnd) else randomString(rnd, 12),
        type = MessageType.entries[rnd.nextInt(MessageType.entries.size)],
        timestamp = if (poolOnly) CDL_LONG_POOL118[rnd.nextInt(CDL_LONG_POOL118.size)]
            else rnd.nextLong(),
        status = MessageStatus.entries[rnd.nextInt(MessageStatus.entries.size)]
    )
}

private fun genCdlUser118(rnd: Random, poolOnly: Boolean): User {
    val nickname = when (rnd.nextInt(5)) {
        0 -> null
        1 -> ""
        2 -> "  "
        3 -> "\u00A0"
        else -> randomToken(rnd)
    }
    return User(
        id = if (poolOnly) CDL_ID_POOL118[rnd.nextInt(CDL_ID_POOL118.size)]
            else randomToken(rnd),
        name = when (rnd.nextInt(4)) {
            0 -> ""
            1 -> "  "
            2 -> "\u00A0"
            else -> randomToken(rnd)
        },
        avatar = if (rnd.nextBoolean()) null else randomToken(rnd),
        email = randomToken(rnd),
        isOnline = rnd.nextBoolean(),
        status = randomToken(rnd),
        nickname = nickname,
        lastSeen = if (poolOnly) CDL_LONG_POOL118[rnd.nextInt(CDL_LONG_POOL118.size)]
            else rnd.nextLong()
    )
}

private fun genCdlMessageList118(rnd: Random, poolOnly: Boolean): List<Message> {
    val n = rnd.nextInt(0, 5)
    return List(n) { genCdlMessage118(rnd, poolOnly) }
}

private fun genCdlUserList118(rnd: Random, poolOnly: Boolean): List<User> {
    val n = rnd.nextInt(0, 4)
    return List(n) { genCdlUser118(rnd, poolOnly) }
}

private fun genCdlNickMap118(rnd: Random, poolOnly: Boolean): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val n = rnd.nextInt(0, 4)
    repeat(n) {
        val k = if (poolOnly) CDL_ID_POOL118[rnd.nextInt(CDL_ID_POOL118.size)]
            else randomToken(rnd)
        out[k] = randomToken(rnd)
    }
    return out
}

private fun genCdlIdSet118(rnd: Random, poolOnly: Boolean): Set<String> {
    val out = LinkedHashSet<String>()
    val n = rnd.nextInt(0, 4)
    repeat(n) {
        out.add(
            if (poolOnly) CDL_ID_POOL118[rnd.nextInt(CDL_ID_POOL118.size)]
            else randomToken(rnd)
        )
    }
    return out
}

    @Test
    fun chatDetailListDerivationsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val messages = genCdlMessageList118(rnd, poolOnly = false)
            assertEquals(
                cdlByIdEncode(cdlResolveMessagesByIdOracle(messages)),
                cdlByIdEncode(resolveMessagesById(messages))
            )
            val users = genCdlUserList118(rnd, poolOnly = false)
            val nicks = genCdlNickMap118(rnd, poolOnly = false)
            val isGroup = rnd.nextBoolean()
            val participants: List<User>? = if (rnd.nextInt(8) == 0) null else users
            assertEquals(
                cdlNamesEncode(cdlResolveParticipantNamesByIdOracle(participants, nicks, isGroup)),
                cdlNamesEncode(resolveParticipantNamesById(participants, nicks, isGroup))
            )
            val selected = genCdlIdSet118(rnd, poolOnly = false)
            assertEquals(
                cdlIdsEncode(cdlResolveSelectedMessagesOracle(messages, selected)),
                cdlIdsEncode(resolveSelectedMessages(messages, selected))
            )
            assertEquals(
                cdlIsMessageSelectionModeOracle(selected),
                isMessageSelectionMode(selected)
            )
        }
    }

    @Test
    fun chatDetailListDerivationsEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            val messages = genCdlMessageList118(rnd, poolOnly = true)
            assertEquals(
                cdlByIdEncode(cdlResolveMessagesByIdOracle(messages)),
                cdlByIdEncode(resolveMessagesById(messages))
            )
            val users = genCdlUserList118(rnd, poolOnly = true)
            val nicks = genCdlNickMap118(rnd, poolOnly = true)
            val isGroup = rnd.nextBoolean()
            val participants: List<User>? = if (rnd.nextInt(4) == 0) null else users
            assertEquals(
                cdlNamesEncode(cdlResolveParticipantNamesByIdOracle(participants, nicks, isGroup)),
                cdlNamesEncode(resolveParticipantNamesById(participants, nicks, isGroup))
            )
            val selected = genCdlIdSet118(rnd, poolOnly = true)
            assertEquals(
                cdlIdsEncode(cdlResolveSelectedMessagesOracle(messages, selected)),
                cdlIdsEncode(resolveSelectedMessages(messages, selected))
            )
            assertEquals(
                cdlIsMessageSelectionModeOracle(selected),
                isMessageSelectionMode(selected)
            )
        }
    }

    @Test
    fun chatDetailListDerivationsPinned() {
        val m1 = Message(id = "m1", chatId = "c", senderId = "u", content = "first")
        val m1b = Message(id = "m1", chatId = "c", senderId = "u", content = "second")
        val m2 = Message(id = "m2", chatId = "c", senderId = "u", content = "two")
        // Duplicate ids: last message wins, first-seen position kept.
        val byId = resolveMessagesById(listOf(m1, m2, m1b))
        assertEquals(listOf("m1", "m2"), byId.keys.toList())
        assertEquals("second", byId["m1"]!!.content)
        assertEquals("two", byId["m2"]!!.content)
        assertEquals(byId, cdlResolveMessagesByIdOracle(listOf(m1, m2, m1b)))
        // displayName: blank nickname falls through to name, blank
        // name falls through to id; NBSP counts as blank.
        val uNick = User(id = "u1", name = "Name", nickname = "Nick")
        val uBlankNick = User(id = "u2", name = "Name", nickname = "\u00A0")
        val uBlankBoth = User(id = "u3", name = "\u00A0", nickname = "")
        assertEquals("Nick", resolveParticipantNamesById(listOf(uNick), emptyMap(), false)["u1"])
        assertEquals("Name", resolveParticipantNamesById(listOf(uBlankNick), emptyMap(), false)["u2"])
        assertEquals("u3", resolveParticipantNamesById(listOf(uBlankBoth), emptyMap(), false)["u3"])
        assertEquals("u3", cdlDisplayNameOracle(uBlankBoth))
        // Group nickname overlay: existing keys keep base position,
        // value replaced; new keys appended.
        val names = resolveParticipantNamesById(
            listOf(uNick, User(id = "u2", name = "Two")),
            mapOf("u2" to "GroupTwo", "u9" to "NewNine"),
            true
        )
        assertEquals(listOf("u1", "u2", "u9"), names.keys.toList())
        assertEquals("Nick", names["u1"])
        assertEquals("GroupTwo", names["u2"])
        assertEquals("NewNine", names["u9"])
        assertEquals(names, cdlResolveParticipantNamesByIdOracle(
            listOf(uNick, User(id = "u2", name = "Two")),
            mapOf("u2" to "GroupTwo", "u9" to "NewNine"),
            true
        ))
        // Non-group ignores the nickname map; empty map ignores it too.
        assertEquals(
            mapOf("u1" to "Nick"),
            resolveParticipantNamesById(listOf(uNick), mapOf("u1" to "X"), false)
        )
        assertEquals(
            mapOf("u1" to "Nick"),
            resolveParticipantNamesById(listOf(uNick), emptyMap(), true)
        )
        // Null participants -> base is empty, but a non-empty group
        // nickname map still overlays onto it.
        assertEquals(mapOf("u1" to "X"), resolveParticipantNamesById(null, mapOf("u1" to "X"), true))
        assertEquals(emptyMap(), resolveParticipantNamesById(null, emptyMap(), true))
        // Selected messages keep source order and do NOT dedupe ids.
        assertEquals(
            listOf(m1, m1b, m2),
            resolveSelectedMessages(listOf(m1, m1b, m2), setOf("m1", "m2"))
        )
        assertEquals(listOf(m1), resolveSelectedMessages(listOf(m1, m2), setOf("m1")))
        assertEquals(emptyList(), resolveSelectedMessages(listOf(m1, m2), emptySet()))
        assertEquals(false, isMessageSelectionMode(emptySet()))
        assertEquals(true, isMessageSelectionMode(setOf("m1")))
    }
}
