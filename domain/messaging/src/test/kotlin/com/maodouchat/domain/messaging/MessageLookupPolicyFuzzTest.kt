// Fuzz / random-oracle tests for MessageLookupPolicy
// (checklist line 931, 131st block). Test-only, zero production changes.
// The oracle is hand-written from the production source: first message
// whose id matches (exact UTF-16 unit equality), optionally also
// restricted to a type set. No MessageLookupPolicy fuzz test existed
// before this block (content-grep dedup 0 hits on all 137 remote
// branches, main's 127 FuzzTest files, and all 134 open PR
// titles/bodies).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

private fun msglByIdOracle(messages: List<Message>, messageId: String): Message? =
    messages.firstOrNull { it.id == messageId }

private fun msglByIdTypeOracle(messages: List<Message>, messageId: String, types: List<MessageType>): Message? =
    messages.firstOrNull { it.id == messageId && it.type in types }

// ---------- generators (mirrored by Oracle131 GEN, seed 131002) ----------

private val MSGL_ID_POOL = arrayOf(
    "", " ", "m1", "m2", "a", "b", "a\uD800b", "\uDC00", "\uE000",
    "\uD83D\uDE00", "m10", "m2", "  ", "\u00A0"
)

private val MSGL_SENDER_POOL = arrayOf("", "u1", "u2", "alice", " ", "a\uD800b")

private val MSGL_TS_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 359999L, 360000L, 360001L,
    86399999L, 86400000L, 86400001L, -86400000L, Long.MAX_VALUE - 1, Long.MAX_VALUE, 172800000L
)

private val MSGL_TYPE_NAMES = arrayOf(
    "TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION", "VOICE",
    "VIDEO", "FILE", "NUDGE", "SK_DIST", "SYSTEM", "REVOKED"
)

private fun genMsglId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MSGL_ID_POOL[rnd.nextInt(MSGL_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genMsglSender(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MSGL_SENDER_POOL[rnd.nextInt(MSGL_SENDER_POOL.size)]
    else randomText(rnd, 6)

private fun genMsglTs(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) MSGL_TS_POOL[rnd.nextInt(MSGL_TS_POOL.size)]
    else rnd.nextLong(-1_000_000_000L, 1_000_000_001L)

private fun genMsglType(rnd: Random): String =
    MSGL_TYPE_NAMES[rnd.nextInt(MSGL_TYPE_NAMES.size)]

private fun genMsglMsg(rnd: Random): Message = Message(
    id = genMsglId(rnd),
    chatId = "c",
    senderId = genMsglSender(rnd),
    content = "",
    type = MessageType.valueOf(genMsglType(rnd)),
    timestamp = genMsglTs(rnd)
)

private fun genMsglList(rnd: Random): List<Message> {
    val n = rnd.nextInt(0, 7)
    return List(n) { genMsglMsg(rnd) }
}

private fun genMsglTypes(rnd: Random): List<MessageType> {
    val n = rnd.nextInt(0, 4)
    return List(n) { MessageType.valueOf(genMsglType(rnd)) }
}

class MessageLookupPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Empty list finds nothing, whatever the id.
        assertNull(MessageLookupPolicy.findById(emptyList(), "m1"))
        assertNull(MessageLookupPolicy.findByIdAndType(emptyList(), "m1", MessageType.TEXT))
        val m1 = Message("m1", "c", "u1", "", MessageType.TEXT, 0L)
        val m2 = Message("m2", "c", "u1", "", MessageType.IMAGE, 1L)
        assertEquals(m1, MessageLookupPolicy.findById(listOf(m1, m2), "m1"))
        assertEquals(m2, MessageLookupPolicy.findById(listOf(m1, m2), "m2"))
        assertNull(MessageLookupPolicy.findById(listOf(m1, m2), "m3"))
        assertNull(MessageLookupPolicy.findById(listOf(m1, m2), "M1"))
        assertNull(MessageLookupPolicy.findById(listOf(m1, m2), " m1"))
        // First match wins when ids repeat.
        val dup = Message("m1", "c", "u2", "", MessageType.VIDEO, 2L)
        assertEquals(m1, MessageLookupPolicy.findById(listOf(m1, dup), "m1"))
        assertEquals(dup, MessageLookupPolicy.findById(listOf(dup, m1), "m1"))
        // Blank ids are ordinary ids, matched exactly.
        val blank = Message("", "c", "u1", "", MessageType.TEXT, 0L)
        assertEquals(blank, MessageLookupPolicy.findById(listOf(blank, m1), ""))
        assertNull(MessageLookupPolicy.findById(listOf(blank, m1), " "))
        // Astral id equals its lone-surrogate pair (UTF-16 unit equality).
        val astral = Message("\uD83D\uDE00", "c", "u1", "", MessageType.TEXT, 0L)
        assertEquals(astral, MessageLookupPolicy.findById(listOf(astral), "\uD83D\uDE00"))
        // Type restriction: empty set never matches, wrong type never matches.
        assertNull(MessageLookupPolicy.findByIdAndType(listOf(m1, m2), "m1"))
        assertEquals(m1, MessageLookupPolicy.findByIdAndType(listOf(m1, m2), "m1", MessageType.TEXT))
        assertNull(MessageLookupPolicy.findByIdAndType(listOf(m1, m2), "m1", MessageType.IMAGE))
        assertEquals(m2, MessageLookupPolicy.findByIdAndType(listOf(m1, m2), "m2", MessageType.TEXT, MessageType.IMAGE))
        assertEquals(m1, MessageLookupPolicy.findByIdAndType(listOf(m1, m2), "m1", MessageType.TEXT, MessageType.TEXT))
        // First id match with a disallowed type is skipped, the later one wins.
        val m1Img = Message("m1", "c", "u1", "", MessageType.IMAGE, 3L)
        assertEquals(m1Img, MessageLookupPolicy.findByIdAndType(listOf(m1, m1Img), "m1", MessageType.IMAGE))
        assertEquals(m1, MessageLookupPolicy.findByIdAndType(listOf(m1, m1Img), "m1", MessageType.TEXT))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle131.genMsgl consumption order exactly, so seed
        // 131002 replays the same 1500 GEN lines drive131.py ran.
        val rnd = Random(131002)
        repeat(1500) {
            when (rnd.nextInt(2)) {
                0 -> {
                    val list = genMsglList(rnd)
                    val id = genMsglId(rnd)
                    assertEquals(msglByIdOracle(list, id), MessageLookupPolicy.findById(list, id))
                }
                else -> {
                    val list = genMsglList(rnd)
                    val id = genMsglId(rnd)
                    val types = genMsglTypes(rnd)
                    assertEquals(
                        msglByIdTypeOracle(list, id, types),
                        MessageLookupPolicy.findByIdAndType(list, id, *types.toTypedArray())
                    )
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Lists of length 0..2 over a small universe, all targets and type sets.
        val universe = ArrayList<Message>()
        for (mid in arrayOf("", "m1")) {
            for (typ in arrayOf("TEXT", "SK_DIST")) {
                for (ts in arrayOf(0L, 86400000L)) {
                    universe.add(Message(mid, "c", "u1", "", MessageType.valueOf(typ), ts))
                }
            }
        }
        for (target in arrayOf("", "m1", "zz")) {
            assertEquals(msglByIdOracle(emptyList(), target), MessageLookupPolicy.findById(emptyList(), target))
            assertEquals(
                msglByIdTypeOracle(emptyList(), target, emptyList()),
                MessageLookupPolicy.findByIdAndType(emptyList(), target)
            )
        }
        val typeSets = listOf(
            emptyList<MessageType>(),
            listOf(MessageType.TEXT),
            listOf(MessageType.SK_DIST),
            listOf(MessageType.TEXT, MessageType.SK_DIST),
            listOf(MessageType.REVOKED)
        )
        for (m in universe) {
            for (target in arrayOf("", "m1", "zz")) {
                for (types in typeSets) {
                    assertEquals(
                        msglByIdOracle(listOf(m), target),
                        MessageLookupPolicy.findById(listOf(m), target)
                    )
                    assertEquals(
                        msglByIdTypeOracle(listOf(m), target, types),
                        MessageLookupPolicy.findByIdAndType(listOf(m), target, *types.toTypedArray())
                    )
                }
            }
        }
        for (m1 in universe) {
            for (m2 in universe) {
                for (target in arrayOf("", "m1", "zz")) {
                    assertEquals(
                        msglByIdOracle(listOf(m1, m2), target),
                        MessageLookupPolicy.findById(listOf(m1, m2), target)
                    )
                    assertEquals(
                        msglByIdTypeOracle(listOf(m1, m2), target, listOf(MessageType.TEXT, MessageType.SK_DIST)),
                        MessageLookupPolicy.findByIdAndType(listOf(m1, m2), target, MessageType.TEXT, MessageType.SK_DIST)
                    )
                }
            }
        }
        // Hand-built edge lists (dup ids, blank ids, astral vs BMP, lone surrogates).
        val edgeLists = listOf(
            listOf(
                Message("m1", "c", "u1", "", MessageType.TEXT, 0L),
                Message("m1", "c", "u1", "", MessageType.TEXT, 1L)
            ),
            listOf(
                Message("", "c", "u1", "", MessageType.TEXT, 0L),
                Message(" ", "c", "u1", "", MessageType.TEXT, 1L)
            ),
            listOf(
                Message("b", "c", "u1", "", MessageType.TEXT, 359999L),
                Message("a", "c", "u1", "", MessageType.TEXT, 0L)
            ),
            listOf(
                Message("\uD83D\uDE00", "c", "u1", "", MessageType.TEXT, 0L),
                Message("\uE000", "c", "u1", "", MessageType.TEXT, 0L)
            ),
            listOf(
                Message("\uE000", "c", "u1", "", MessageType.TEXT, 0L),
                Message("\uD83D\uDE00", "c", "u1", "", MessageType.TEXT, 0L)
            ),
            listOf(
                Message("a\uD800b", "c", "u1", "", MessageType.TEXT, 0L),
                Message("\uDC00", "c", "u1", "", MessageType.TEXT, 0L)
            ),
            listOf(
                Message("m1", "c", "u1", "", MessageType.TEXT, 0L),
                Message("m2", "c", "u1", "", MessageType.TEXT, 86400000L)
            ),
            listOf(
                Message("m1", "c", "u1", "", MessageType.TEXT, 0L),
                Message("m1", "c", "u1", "", MessageType.SK_DIST, 0L)
            ),
            listOf(
                Message("m1", "c", "u1", "", MessageType.TEXT, Long.MIN_VALUE),
                Message("m2", "c", "u1", "", MessageType.TEXT, Long.MAX_VALUE)
            )
        )
        for (msgs in edgeLists) {
            for (target in arrayOf("", "m1", "m2", "a", "zz", "\uD83D\uDE00", "a\uD800b")) {
                assertEquals(
                    msglByIdOracle(msgs, target),
                    MessageLookupPolicy.findById(msgs, target)
                )
                assertEquals(
                    msglByIdTypeOracle(msgs, target, listOf(MessageType.TEXT)),
                    MessageLookupPolicy.findByIdAndType(msgs, target, MessageType.TEXT)
                )
                assertEquals(
                    msglByIdTypeOracle(msgs, target, listOf(MessageType.SK_DIST, MessageType.TEXT, MessageType.TEXT)),
                    MessageLookupPolicy.findByIdAndType(msgs, target, MessageType.SK_DIST, MessageType.TEXT, MessageType.TEXT)
                )
            }
        }
    }
}
