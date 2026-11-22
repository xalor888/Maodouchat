// Fuzz / random-oracle tests for NotificationSlotPolicy (checklist line 931,
// 78th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. every slot id is a template over String.hashCode() (missed-call ids xor 0x4D495353, quick-reply +2 / mark-read +1); message id is the constant 0; sound gates are pure boolean conjunctions (in-app tone additionally requires !foreground).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationSlotPolicyFuzzTest {

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

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- NotificationSlotPolicy / NotificationSoundPolicy oracle ----------

private fun messageTagOracle(chatId: String): String = "maodouchat_" + chatId

private fun reminderTagOracle(chatId: String): String = "maodouchat_reminder_" + chatId

private fun reminderNotifyIdOracle(messageId: String): Int = hashCodeOracle(messageId)

private fun reminderRequestCodeOracle(chatId: String): Int = hashCodeOracle("reminder_" + chatId)

private fun chatRequestCodeOracle(chatId: String): Int = hashCodeOracle(chatId)

private fun chatDataUriOracle(chatId: String): String = "maodouchat-notify://chat/" + chatId

private fun reminderDataUriOracle(chatId: String, messageId: String): String =
    "maodouchat-notify://reminder/" + chatId + "/" + messageId

private fun quickReplyRequestCodeOracle(chatId: String): Int = hashCodeOracle(chatId) + 2

private fun quickReplyDataUriOracle(chatId: String): String =
    "maodouchat-notify-reply://chat/" + chatId

private fun markReadRequestCodeOracle(chatId: String): Int = hashCodeOracle(chatId) + 1

private fun markReadDataUriOracle(chatId: String): String =
    "maodouchat-notify-read://chat/" + chatId

private fun incomingCallNotifyIdOracle(callId: String): Int = hashCodeOracle(callId)

private fun missedCallNotifyIdOracle(callId: String): Int =
    hashCodeOracle(callId) xor 0x4D495353

private fun incomingCallDataUriOracle(callId: String): String =
    "maodouchat-notify://incoming/" + callId

private fun missedCallDataUriOracle(callId: String): String =
    "maodouchat-notify://missed/" + callId

private fun postNotifyIdOracle(postId: String): Int = hashCodeOracle("post_" + postId)

private fun postRequestCodeOracle(postId: String): Int = hashCodeOracle(postId)

private fun postDataUriOracle(postId: String): String = "maodouchat-notify://post/" + postId

private fun aiTaskNotifyIdOracle(taskId: String): Int = hashCodeOracle(taskId)

private fun aiTaskRequestCodeOracle(taskId: String): Int = hashCodeOracle(taskId)

private fun aiTaskGroupKeyOracle(chatId: String): String = "ai_tasks_" + chatId

private fun aiTaskSummaryIdOracle(chatId: String): Int = hashCodeOracle("ai_summary_" + chatId)

private fun aiTaskDataUriOracle(taskId: String): String = "maodouchat-notify://aitask/" + taskId

private fun friendRequestNotifyIdOracle(requestId: String): Int = hashCodeOracle(requestId)

private fun friendRequestRequestCodeOracle(requestId: String): Int =
    hashCodeOracle("friend_" + requestId)

private fun friendRequestDataUriOracle(requestId: String): String =
    "maodouchat-notify://friend/" + requestId

private fun groupInviteNotifyIdOracle(inviteId: String): Int = hashCodeOracle(inviteId)

private fun groupInviteRequestCodeOracle(inviteId: String): Int =
    hashCodeOracle("group_invite_" + inviteId)

private fun groupInviteDataUriOracle(inviteId: String): String =
    "maodouchat-notify://group-invite/" + inviteId

private fun announcementNotifyIdOracle(announcementId: String): Int = hashCodeOracle(announcementId)

private fun announcementRequestCodeOracle(announcementId: String): Int =
    hashCodeOracle("announcement_" + announcementId)

private fun announcementDataUriOracle(announcementId: String): String =
    "maodouchat-notify://announcement/" + announcementId

private fun scheduledMessageFailedNotifyIdOracle(): Int =
    hashCodeOracle("scheduled_message_failed")

private fun messageSoundEnabledOracle(runtimeFlagEnabled: Boolean, userPreferenceEnabled: Boolean): Boolean =
    runtimeFlagEnabled && userPreferenceEnabled

private fun trayMessageSoundEnabledOracle(
    runtimeFlagEnabled: Boolean,
    userPreferenceEnabled: Boolean,
    chatMuted: Boolean,
): Boolean = messageSoundEnabledOracle(runtimeFlagEnabled, userPreferenceEnabled) && !chatMuted

private fun ringtoneEnabledOracle(runtimeFlagEnabled: Boolean, userPreferenceEnabled: Boolean): Boolean =
    runtimeFlagEnabled && userPreferenceEnabled

private fun inAppReceiveToneEnabledOracle(
    inAppSoundsFlag: Boolean,
    notificationsEnabled: Boolean,
    notificationSoundFlag: Boolean,
    soundPreference: Boolean,
    appInForeground: Boolean = false,
): Boolean =
    !appInForeground &&
        inAppSoundsFlag &&
        notificationsEnabled &&
        messageSoundEnabledOracle(notificationSoundFlag, soundPreference)

private fun traySoundAllowedOracle(
    appInForeground: Boolean,
    preferenceSoundEnabled: Boolean,
): Boolean = !appInForeground && preferenceSoundEnabled

private val NOTIFY_ID_POOL = listOf(
    "", " ", "chat1", "chat-1", "a", "ab", "abc", "user_42", "post_9",
    "call-xyz", "task-1", "req-7", "inv-3", "ann-2", "msg-100",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "😀", "chat/1", "chat 1",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
)

private fun genNotifyId(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> NOTIFY_ID_POOL[rnd.nextInt(NOTIFY_ID_POOL.size)]
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        8 -> "a".repeat(rnd.nextInt(0, 64))
        else -> randomString(rnd, 24)
    }

    @Test
    fun notifyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val id = genNotifyId(rnd)
            val other = genNotifyId(rnd)
            assertEquals(messageTagOracle(id), NotificationSlotPolicy.messageTag(id))
            assertEquals(0, NotificationSlotPolicy.messageNotifyId())
            assertEquals(reminderTagOracle(id), NotificationSlotPolicy.reminderTag(id))
            assertEquals(reminderNotifyIdOracle(id), NotificationSlotPolicy.reminderNotifyId(id))
            assertEquals(reminderRequestCodeOracle(id), NotificationSlotPolicy.reminderRequestCode(id))
            assertEquals(chatRequestCodeOracle(id), NotificationSlotPolicy.chatRequestCode(id))
            assertEquals(chatDataUriOracle(id), NotificationSlotPolicy.chatDataUri(id))
            assertEquals(reminderDataUriOracle(id, other), NotificationSlotPolicy.reminderDataUri(id, other))
            assertEquals(quickReplyRequestCodeOracle(id), NotificationSlotPolicy.quickReplyRequestCode(id))
            assertEquals(quickReplyDataUriOracle(id), NotificationSlotPolicy.quickReplyDataUri(id))
            assertEquals(markReadRequestCodeOracle(id), NotificationSlotPolicy.markReadRequestCode(id))
            assertEquals(markReadDataUriOracle(id), NotificationSlotPolicy.markReadDataUri(id))
            assertEquals(incomingCallNotifyIdOracle(id), NotificationSlotPolicy.incomingCallNotifyId(id))
            assertEquals(missedCallNotifyIdOracle(id), NotificationSlotPolicy.missedCallNotifyId(id))
            assertEquals(incomingCallDataUriOracle(id), NotificationSlotPolicy.incomingCallDataUri(id))
            assertEquals(missedCallDataUriOracle(id), NotificationSlotPolicy.missedCallDataUri(id))
            assertEquals(postNotifyIdOracle(id), NotificationSlotPolicy.postNotifyId(id))
            assertEquals(postRequestCodeOracle(id), NotificationSlotPolicy.postRequestCode(id))
            assertEquals(postDataUriOracle(id), NotificationSlotPolicy.postDataUri(id))
            assertEquals(aiTaskNotifyIdOracle(id), NotificationSlotPolicy.aiTaskNotifyId(id))
            assertEquals(aiTaskRequestCodeOracle(id), NotificationSlotPolicy.aiTaskRequestCode(id))
            assertEquals(aiTaskGroupKeyOracle(id), NotificationSlotPolicy.aiTaskGroupKey(id))
            assertEquals(aiTaskSummaryIdOracle(id), NotificationSlotPolicy.aiTaskSummaryId(id))
            assertEquals(aiTaskDataUriOracle(id), NotificationSlotPolicy.aiTaskDataUri(id))
            assertEquals(friendRequestNotifyIdOracle(id), NotificationSlotPolicy.friendRequestNotifyId(id))
            assertEquals(friendRequestRequestCodeOracle(id), NotificationSlotPolicy.friendRequestRequestCode(id))
            assertEquals(friendRequestDataUriOracle(id), NotificationSlotPolicy.friendRequestDataUri(id))
            assertEquals(groupInviteNotifyIdOracle(id), NotificationSlotPolicy.groupInviteNotifyId(id))
            assertEquals(groupInviteRequestCodeOracle(id), NotificationSlotPolicy.groupInviteRequestCode(id))
            assertEquals(groupInviteDataUriOracle(id), NotificationSlotPolicy.groupInviteDataUri(id))
            assertEquals(announcementNotifyIdOracle(id), NotificationSlotPolicy.announcementNotifyId(id))
            assertEquals(announcementRequestCodeOracle(id), NotificationSlotPolicy.announcementRequestCode(id))
            assertEquals(announcementDataUriOracle(id), NotificationSlotPolicy.announcementDataUri(id))
            assertEquals(
                scheduledMessageFailedNotifyIdOracle(),
                NotificationSlotPolicy.scheduledMessageFailedNotifyId()
            )
        }
    }

    @Test
    fun notifySoundRandom() {
        val rnd = Random(7)
        repeat(300) {
            val a = rnd.nextBoolean()
            val b = rnd.nextBoolean()
            val c = rnd.nextBoolean()
            val d = rnd.nextBoolean()
            val fg = rnd.nextBoolean()
            assertEquals(messageSoundEnabledOracle(a, b), NotificationSoundPolicy.messageSoundEnabled(a, b))
            assertEquals(trayMessageSoundEnabledOracle(a, b, c), NotificationSoundPolicy.trayMessageSoundEnabled(a, b, c))
            assertEquals(ringtoneEnabledOracle(a, b), NotificationSoundPolicy.ringtoneEnabled(a, b))
            assertEquals(
                inAppReceiveToneEnabledOracle(a, b, c, d, fg),
                NotificationSoundPolicy.inAppReceiveToneEnabled(a, b, c, d, fg)
            )
            assertEquals(
                inAppReceiveToneEnabledOracle(a, b, c, d),
                NotificationSoundPolicy.inAppReceiveToneEnabled(a, b, c, d)
            )
            assertEquals(traySoundAllowedOracle(fg, b), NotificationSoundPolicy.traySoundAllowed(fg, b))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // String.hashCode() values pinned (Java hash: 31*h + unit).
        assertEquals("maodouchat_chat1", NotificationSlotPolicy.messageTag("chat1"))
        assertEquals(0, NotificationSlotPolicy.messageNotifyId())
        assertEquals("maodouchat_reminder_chat1", NotificationSlotPolicy.reminderTag("chat1"))
        assertEquals("chat1".hashCode(), NotificationSlotPolicy.chatRequestCode("chat1"))
        assertEquals("chat1".hashCode() + 2, NotificationSlotPolicy.quickReplyRequestCode("chat1"))
        assertEquals("chat1".hashCode() + 1, NotificationSlotPolicy.markReadRequestCode("chat1"))
        assertEquals("call-xyz".hashCode(), NotificationSlotPolicy.incomingCallNotifyId("call-xyz"))
        assertEquals(
            "call-xyz".hashCode() xor 0x4D495353,
            NotificationSlotPolicy.missedCallNotifyId("call-xyz")
        )
        assertEquals(
            "post_post-9".hashCode(),
            NotificationSlotPolicy.postNotifyId("post-9")
        )
        assertEquals("ai_tasks_chat1", NotificationSlotPolicy.aiTaskGroupKey("chat1"))
        assertEquals(
            "ai_summary_chat1".hashCode(),
            NotificationSlotPolicy.aiTaskSummaryId("chat1")
        )
        assertEquals(
            "scheduled_message_failed".hashCode(),
            NotificationSlotPolicy.scheduledMessageFailedNotifyId()
        )
        // Empty id hashes to 0; xor salt survives.
        assertEquals(0, NotificationSlotPolicy.chatRequestCode(""))
        assertEquals(0x4D495353, NotificationSlotPolicy.missedCallNotifyId(""))
        assertEquals("post_".hashCode(), NotificationSlotPolicy.postNotifyId(""))
        // URIs are plain templates.
        assertEquals(
            "maodouchat-notify://reminder/chat1/msg-100",
            NotificationSlotPolicy.reminderDataUri("chat1", "msg-100")
        )
        assertEquals(
            "maodouchat-notify-reply://chat/chat1",
            NotificationSlotPolicy.quickReplyDataUri("chat1")
        )
        assertEquals(
            "maodouchat-notify-read://chat/chat1",
            NotificationSlotPolicy.markReadDataUri("chat1")
        )
        // Constants pinned literally.
        assertEquals("maodouchat_", NotificationSlotPolicy.MESSAGE_TAG_PREFIX)
        assertEquals("maodouchat_ai_task", NotificationSlotPolicy.AI_TASK_TAG)
        assertEquals("maodouchat_friend_request", NotificationSlotPolicy.FRIEND_REQUEST_TAG)
        assertEquals("maodouchat_group_invite", NotificationSlotPolicy.GROUP_INVITE_TAG)
        assertEquals("maodouchat_announcement", NotificationSlotPolicy.ANNOUNCEMENT_TAG)
        assertEquals("maodouchat_call", NotificationSlotPolicy.CALL_TAG)
        assertEquals("maodouchat_missed", NotificationSlotPolicy.MISSED_CALL_TAG)
        assertEquals("maodouchat_post", NotificationSlotPolicy.POST_TAG)
        assertEquals("maodouchat_test", NotificationSlotPolicy.TEST_TAG)
        assertEquals(0x4D495353, NotificationSlotPolicy.MISSED_CALL_ID_SALT)
        // Sound gates: all-true alone passes; any false (or muted /
        // foreground) blocks.
        assertEquals(true, NotificationSoundPolicy.messageSoundEnabled(true, true))
        assertEquals(false, NotificationSoundPolicy.messageSoundEnabled(true, false))
        assertEquals(false, NotificationSoundPolicy.messageSoundEnabled(false, true))
        assertEquals(false, NotificationSoundPolicy.trayMessageSoundEnabled(true, true, true))
        assertEquals(true, NotificationSoundPolicy.trayMessageSoundEnabled(true, true, false))
        assertEquals(true, NotificationSoundPolicy.ringtoneEnabled(true, true))
        assertEquals(
            true,
            NotificationSoundPolicy.inAppReceiveToneEnabled(true, true, true, true)
        )
        assertEquals(
            false,
            NotificationSoundPolicy.inAppReceiveToneEnabled(true, true, true, true, true)
        )
        assertEquals(
            false,
            NotificationSoundPolicy.inAppReceiveToneEnabled(true, true, true, false)
        )
        assertEquals(false, NotificationSoundPolicy.traySoundAllowed(true, true))
        assertEquals(true, NotificationSoundPolicy.traySoundAllowed(false, true))
    }
}
