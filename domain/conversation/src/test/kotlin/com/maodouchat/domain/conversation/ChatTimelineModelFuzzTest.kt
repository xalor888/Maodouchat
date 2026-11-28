// Fuzz / random-oracle tests for ChatTimelineModel
// (checklist line 931, 130th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: SK_DIST
// filtering, the 6-minute same-sender grouping (with the date-label
// conjunct that sets sameSenderGroup apart from the fuzz-40
// isGroupedWithPrevious), chronological ordering, duplicate-id
// dedup, date separators, and the once-only unread separator. No
// ChatTimelineModel fuzz test existed before this block.
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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

// Day label used by the probe as labelForTimestamp: same function in
// both, truncating division so negative timestamps label by day too.
private fun ctmLabel(ts: Long): String = "D" + (ts / 86_400_000L)

private fun ctmVisibleOracle(message: Message): Boolean =
    message.type != MessageType.SK_DIST

private fun ctmGroupOracle(
    first: Message,
    second: Message,
    firstDateLabel: String,
    secondDateLabel: String,
    windowMs: Long
): Boolean =
    first.senderId == second.senderId &&
        firstDateLabel == secondDateLabel &&
        abs(second.timestamp - first.timestamp) < windowMs

private fun ctmBuildOracle(
    messages: List<Message>,
    unreadSeparatorId: String? = null
): List<ChatItem> {
    val visible = messages.filter(::ctmVisibleOracle)
    if (visible.isEmpty()) return emptyList()
    var chronological = true
    for (index in 1 until visible.size) {
        val first = visible[index - 1]
        val second = visible[index]
        if (first.timestamp > second.timestamp || (first.timestamp == second.timestamp && first.id > second.id)) {
            chronological = false
            break
        }
    }
    val sorted = if (chronological) visible else visible.sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    val deduped = ArrayList<Message>(sorted.size)
    val seenIds = HashSet<String>(sorted.size)
    for (message in sorted) {
        val id = message.id
        if (id.isBlank()) {
            deduped += message
        } else if (seenIds.add(id)) {
            deduped += message
        }
    }
    if (deduped.isEmpty()) return emptyList()
    val result = ArrayList<ChatItem>(deduped.size + 8)
    var previousDateLabel: String? = null
    var blankOccurrence = 0
    var unreadPlaced = unreadSeparatorId.isNullOrBlank()
    val dateLabels = Array(deduped.size) { ctmLabel(deduped[it].timestamp) }
    deduped.forEachIndexed { i, message ->
        val dateLabel = dateLabels[i]
        val startsNewDate = dateLabel != previousDateLabel
        if (startsNewDate) {
            result += ChatItem.DateSeparator(dateLabel, "${result.size}_$dateLabel")
        }
        if (!unreadPlaced && message.id == unreadSeparatorId) {
            result += ChatItem.UnreadSeparator(message.id)
            unreadPlaced = true
        }
        val previous = deduped.getOrNull(i - 1)
        val next = deduped.getOrNull(i + 1)
        val startsGroup = previous == null ||
            !ctmGroupOracle(previous, message, dateLabels[i - 1], dateLabel, SENDER_GROUP_WINDOW_MS)
        val endsGroup = next == null ||
            !ctmGroupOracle(message, next, dateLabel, dateLabels[i + 1], SENDER_GROUP_WINDOW_MS)
        val occurrence = if (message.id.isBlank()) ++blankOccurrence else 0
        result += ChatItem.Msg(
            message = message,
            showAvatar = startsGroup,
            showSenderName = startsGroup,
            isGroupEdge = endsGroup,
            occurrence = occurrence
        )
        previousDateLabel = dateLabel
    }
    if (!unreadPlaced && result.isNotEmpty()) {
        val insertAt = if (result.first() is ChatItem.DateSeparator) 1 else 0
        result.add(insertAt, ChatItem.UnreadSeparator(deduped.first().id.ifBlank { "window" }))
    }
    return result
}

// ---------- generators (mirrored by Oracle130 GEN, seed 130003) ----------

private val CTM_ID_POOL = arrayOf(
    "", " ", "m1", "m2", "a", "b", "a\uD800b", "\uDC00", "\uE000",
    "\uD83D\uDE00", "m10", "m2", "  ", "\u00A0"
)

private val CTM_SENDER_POOL = arrayOf("", "u1", "u2", "alice", " ", "a\uD800b")

private val CTM_TS_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, 359_999, 360_000, 360_001,
    86_399_999, 86_400_000, 86_400_001, -86_400_000, Long.MAX_VALUE - 1,
    Long.MAX_VALUE, 172_800_000
)

private val CTM_WINDOW_POOL = longArrayOf(
    0, 1, 360_000, 360_001, -1, Long.MAX_VALUE, Long.MIN_VALUE, 86_400_000
)

private fun genCtmId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CTM_ID_POOL[rnd.nextInt(CTM_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genCtmSender(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CTM_SENDER_POOL[rnd.nextInt(CTM_SENDER_POOL.size)]
    else randomText(rnd, 6)

private fun genCtmTs(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) CTM_TS_POOL[rnd.nextInt(CTM_TS_POOL.size)]
    else rnd.nextLong(-1_000_000_000L, 1_000_000_001L)

private fun genCtmType(rnd: Random): MessageType =
    MessageType.entries[rnd.nextInt(MessageType.entries.size)]

private fun genCtmMsg(rnd: Random): Message = Message(
    id = genCtmId(rnd),
    chatId = "c",
    senderId = genCtmSender(rnd),
    content = "",
    type = genCtmType(rnd),
    timestamp = genCtmTs(rnd)
)

private fun genCtmList(rnd: Random): List<Message> {
    val n = rnd.nextInt(0, 7)
    return List(n) { genCtmMsg(rnd) }
}

private fun genCtmUnread(rnd: Random): String? =
    if (rnd.nextInt(2) == 0) null else genCtmId(rnd)

private fun genCtmWindow(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) CTM_WINDOW_POOL[rnd.nextInt(CTM_WINDOW_POOL.size)]
    else rnd.nextLong(-1_000_000L, 1_000_001L)

class ChatTimelineModelFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Only SK_DIST hides; every other type shows.
        for (type in MessageType.entries) {
            val m = Message("m", "c", "u", "", type, 0L)
            assertEquals(type != MessageType.SK_DIST, isTimelineVisibleMessage(m))
        }
        // Grouping needs same sender, same day label, and a gap strictly
        // under the window; the label conjunct is what sameSenderGroup
        // adds over the fuzz-40 grouping check.
        val a = Message("a", "c", "u1", "", MessageType.TEXT, 0L)
        val b = Message("b", "c", "u1", "", MessageType.TEXT, 359_999L)
        val c = Message("c", "c", "u1", "", MessageType.TEXT, 360_000L)
        val d = Message("d", "c", "u2", "", MessageType.TEXT, 1L)
        assertEquals(true, sameSenderGroup(a, b, "D0", "D0"))
        assertEquals(false, sameSenderGroup(a, c, "D0", "D0"))
        assertEquals(false, sameSenderGroup(a, d, "D0", "D0"))
        assertEquals(false, sameSenderGroup(a, b, "D0", "D1"))
        assertEquals(false, sameSenderGroup(a, b, "D0", "D0", 0L))
        assertEquals(true, sameSenderGroup(a, b, "D0", "D0", Long.MAX_VALUE))
        // Overflow: second - first == MIN_VALUE wraps, and abs(MIN_VALUE)
        // stays negative, so it passes any window above MIN_VALUE.
        val zero = Message("z", "c", "u1", "", MessageType.TEXT, 0L)
        val min = Message("min", "c", "u1", "", MessageType.TEXT, Long.MIN_VALUE)
        assertEquals(true, sameSenderGroup(zero, min, "D0", "D0", 0L))
        assertEquals(false, sameSenderGroup(zero, min, "D0", "D0", Long.MIN_VALUE))
        assertEquals(true, sameSenderGroup(min, zero, "D0", "D0", 0L))
        assertEquals(false, sameSenderGroup(min, zero, "D0", "D0", Long.MIN_VALUE))
        // Empty list builds nothing; SK_DIST-only builds nothing.
        assertEquals(emptyList(), buildChatItems(emptyList(), ::ctmLabel))
        assertEquals(
            emptyList(),
            buildChatItems(listOf(Message("s", "c", "u", "", MessageType.SK_DIST, 0L)), ::ctmLabel)
        )
        // One message: date separator + the message as a lone group.
        val one = buildChatItems(listOf(a), ::ctmLabel)
        assertEquals(2, one.size)
        assertEquals(ChatItem.DateSeparator("D0", "0_D0"), one[0])
        assertEquals(ChatItem.Msg(a, true, true, true, 0), one[1])
        // Duplicate ids dedup to the first; blank ids never dedup.
        val dup = buildChatItems(listOf(a, a.copy(timestamp = 1L)), ::ctmLabel)
        assertEquals(2, dup.size)
        val blanks = buildChatItems(
            listOf(
                Message("", "c", "u1", "", MessageType.TEXT, 0L),
                Message(" ", "c", "u1", "", MessageType.TEXT, 1L)
            ),
            ::ctmLabel
        )
        assertEquals(3, blanks.size)
        assertEquals(ChatItem.Msg(Message("", "c", "u1", "", MessageType.TEXT, 0L), true, true, false, 1), blanks[1])
        assertEquals(ChatItem.Msg(Message(" ", "c", "u1", "", MessageType.TEXT, 1L), false, false, true, 2), blanks[2])
        // Out-of-order input is sorted by (timestamp, id).
        val rev = buildChatItems(listOf(b, a), ::ctmLabel)
        assertEquals(ChatItem.Msg(a, true, true, false, 0), rev[1])
        assertEquals(ChatItem.Msg(b, false, false, true, 0), rev[2])
        // Unread separator lands before its message; an absent unread id
        // falls back to before the first message.
        val withUnread = buildChatItems(listOf(a, b), ::ctmLabel, "b")
        assertEquals(ChatItem.UnreadSeparator("b"), withUnread[2])
        val absentUnread = buildChatItems(listOf(a, b), ::ctmLabel, "zz")
        assertEquals(ChatItem.UnreadSeparator("a"), absentUnread[1])
        // Astral ids sort by UTF-16 unit: U+10000 (D800 DC00) before U+E000.
        val astral = Message("\uD83D\uDE00", "c", "u1", "", MessageType.TEXT, 0L)
        val bmp = Message("\uE000", "c", "u1", "", MessageType.TEXT, 0L)
        val sorted = buildChatItems(listOf(bmp, astral), ::ctmLabel)
        assertEquals(ChatItem.Msg(astral, true, true, false, 0), sorted[1])
        assertEquals(ChatItem.Msg(bmp, false, false, true, 0), sorted[2])
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle130.genCtm consumption order exactly, so seed
        // 130003 replays the same 1500 GEN lines drive130.py ran.
        val rnd = Random(130003)
        repeat(1500) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val m = genCtmMsg(rnd)
                    assertEquals(ctmVisibleOracle(m), isTimelineVisibleMessage(m))
                }
                1 -> {
                    val first = genCtmMsg(rnd)
                    val second = genCtmMsg(rnd)
                    val l1 = ctmLabel(first.timestamp)
                    val l2 = ctmLabel(second.timestamp)
                    val window = genCtmWindow(rnd)
                    assertEquals(
                        ctmGroupOracle(first, second, l1, l2, window),
                        sameSenderGroup(first, second, l1, l2, window)
                    )
                }
                else -> {
                    val list = genCtmList(rnd)
                    val unread = genCtmUnread(rnd)
                    assertEquals(ctmBuildOracle(list, unread), buildChatItems(list, ::ctmLabel, unread))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Visible over every type.
        for (type in MessageType.entries) {
            val m = Message("m", "c", "u", "", type, 0L)
            assertEquals(ctmVisibleOracle(m), isTimelineVisibleMessage(m))
        }
        // Group over a small sender x timestamp x window universe, plus
        // the overflow pair at both window extremes.
        val senders = arrayOf("u1", "u2")
        val times = longArrayOf(-1, 0, 360_000, 86_400_000)
        val windows = longArrayOf(0, 360_000, 360_001)
        for (s1 in senders) {
            for (s2 in senders) {
                for (t1 in times) {
                    for (t2 in times) {
                        for (w in windows) {
                            val m1 = Message("a", "c", s1, "", MessageType.TEXT, t1)
                            val m2 = Message("b", "c", s2, "", MessageType.TEXT, t2)
                            val l1 = ctmLabel(t1)
                            val l2 = ctmLabel(t2)
                            assertEquals(
                                ctmGroupOracle(m1, m2, l1, l2, w),
                                sameSenderGroup(m1, m2, l1, l2, w)
                            )
                        }
                    }
                }
            }
        }
        for (w in longArrayOf(Long.MIN_VALUE, -1, 0, Long.MAX_VALUE)) {
            val lo = Message("lo", "c", "u1", "", MessageType.TEXT, Long.MIN_VALUE)
            val hi = Message("hi", "c", "u1", "", MessageType.TEXT, Long.MAX_VALUE)
            assertEquals(
                ctmGroupOracle(lo, hi, "D0", "D0", w),
                sameSenderGroup(lo, hi, "D0", "D0", w)
            )
            assertEquals(
                ctmGroupOracle(hi, lo, "D0", "D0", w),
                sameSenderGroup(hi, lo, "D0", "D0", w)
            )
        }
        // Build over all lists of length 0..2 from a small universe.
        val ids = arrayOf("", "m1")
        val types = arrayOf(MessageType.TEXT, MessageType.SK_DIST)
        val stamps = longArrayOf(0, 86_400_000)
        val universe = ArrayList<Message>()
        for (id in ids) {
            for (type in types) {
                for (ts in stamps) {
                    universe += Message(id, "c", "u1", "", type, ts)
                }
            }
        }
        val unreads = arrayOf<String?>(null, "", "m1", "zz")
        assertEquals(ctmBuildOracle(emptyList(), null), buildChatItems(emptyList(), ::ctmLabel, null))
        for (m in universe) {
            for (unread in unreads) {
                assertEquals(ctmBuildOracle(listOf(m), unread), buildChatItems(listOf(m), ::ctmLabel, unread))
            }
        }
        for (m1 in universe) {
            for (m2 in universe) {
                for (unread in unreads) {
                    assertEquals(
                        ctmBuildOracle(listOf(m1, m2), unread),
                        buildChatItems(listOf(m1, m2), ::ctmLabel, unread)
                    )
                }
            }
        }
        // Id ordering: astral vs BMP, and blank vs non-blank ids.
        for (u in WS_CHARS) {
            val s = u.toString()
            val m = Message(s, "c", "u1", "", MessageType.TEXT, 0L)
            assertEquals(ctmVisibleOracle(m), isTimelineVisibleMessage(m))
            assertEquals(ctmBuildOracle(listOf(m), null), buildChatItems(listOf(m), ::ctmLabel, null))
        }
    }
}
