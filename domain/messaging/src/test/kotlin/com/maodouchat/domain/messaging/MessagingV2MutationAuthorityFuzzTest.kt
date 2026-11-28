// Fuzz / random-oracle tests for MessagingV2MutationAuthority.canApply
// (checklist line 931, 137th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: non-mutation
// actions pass through; the author always passes; anyone else may only
// DELETE, and only as the "system" principal on device 0 with kind
// "SERVICE". Every comparison is exact: case, spacing, and affixes miss.
// No MessagingV2MutationAuthority fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.messaging

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

private fun canApplyOracle(
    action: String,
    targetSenderUserId: String,
    envelopeSenderUserId: String,
    envelopeSenderDeviceId: Int,
    envelopeKind: String,
): Boolean {
    if (action != "EDIT" && action != "REVOKE" && action != "DELETE") return true
    if (targetSenderUserId == envelopeSenderUserId) return true
    return action == "DELETE" &&
        envelopeKind == "SERVICE" &&
        envelopeSenderDeviceId == 0 &&
        envelopeSenderUserId == "system"
}

// ---------- generators (mirrored by Oracle137 GEN, seed 137003) ----------

private val MMA_ACTION_POOL = arrayOf(
    "", "EDIT", "REVOKE", "DELETE", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT", "edit", "delete",
    "EDIT ", " EDIT", "DELET", "REACTION", "FROBNICATE"
)

private val MMA_ID_POOL = arrayOf(
    "", " ", "alice", "Alice", "system", "System", "mallory", "bot_helper",
    "bot", "a", "a\uD800b", "\uD83D\uDE00", "system ", " system"
)

private val MMA_KIND_POOL = arrayOf(
    "", "SERVICE", "EVENT", "service", "DATA", "SERVICE ", " SERVICE"
)

private val MMA_DEVICE_POOL = intArrayOf(-1, 0, 1, 2, 100)

private fun genMmaAction(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MMA_ACTION_POOL[rnd.nextInt(MMA_ACTION_POOL.size)]
    else randomText(rnd, 14)

private fun genMmaId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MMA_ID_POOL[rnd.nextInt(MMA_ID_POOL.size)]
    else randomText(rnd, 12)

private fun genMmaKind(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MMA_KIND_POOL[rnd.nextInt(MMA_KIND_POOL.size)]
    else randomText(rnd, 10)

private fun genMmaDevice(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) MMA_DEVICE_POOL[rnd.nextInt(MMA_DEVICE_POOL.size)]
    else rnd.nextInt(-5, 6)

class MessagingV2MutationAuthorityFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Author rule: target == envelope always passes, any mutation.
        for (action in listOf("EDIT", "REVOKE", "DELETE")) {
            assertEquals(true, MessagingV2MutationAuthority.canApply(action, "alice", "alice", 1, "EVENT"))
        }
        // Non-mutation actions pass through regardless of sender.
        for (action in listOf("REACTION_SET", "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT", "FROBNICATE", "")) {
            assertEquals(true, MessagingV2MutationAuthority.canApply(action, "alice", "mallory", 1, "EVENT"))
        }
        // Stranger cannot mutate another sender's message.
        for (action in listOf("EDIT", "REVOKE", "DELETE")) {
            assertEquals(false, MessagingV2MutationAuthority.canApply(action, "alice", "mallory", 1, "EVENT"))
        }
        // Trusted service principal may only DELETE, with all four gates.
        assertEquals(true, MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", 0, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("EDIT", "alice", "system", 0, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("REVOKE", "alice", "system", 0, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", 1, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", 0, "EVENT"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("DELETE", "alice", "System", 0, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("DELETE", "alice", "", 0, "SERVICE"))
        assertEquals(false, MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", -1, "SERVICE"))
        // Identity match is case-sensitive.
        assertEquals(false, MessagingV2MutationAuthority.canApply("EDIT", "Alice", "alice", 1, "EVENT"))
        // Device 0 alone buys a non-system sender nothing.
        assertEquals(false, MessagingV2MutationAuthority.canApply("REVOKE", "alice", "mallory", 0, "SERVICE"))
        // The author rule fires first even for the system principal.
        assertEquals(true, MessagingV2MutationAuthority.canApply("EDIT", "system", "system", 0, "SERVICE"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle137.genMma consumption order exactly, so seed
        // 137003 replays the same 1500 GEN lines drive137.py ran.
        val rnd = Random(137003)
        repeat(1500) {
            val action = genMmaAction(rnd)
            val target = genMmaId(rnd)
            val sender = genMmaId(rnd)
            val device = genMmaDevice(rnd)
            val kind = genMmaKind(rnd)
            assertEquals(
                canApplyOracle(action, target, sender, device, kind),
                MessagingV2MutationAuthority.canApply(action, target, sender, device, kind)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every action against the five canonical sender shapes.
        for (action in MMA_ACTION_POOL) {
            checkMmaAction(action)
        }
        // Every id pair for the trusted-delete shape.
        for (target in MMA_ID_POOL) {
            for (sender in MMA_ID_POOL) {
                assertEquals(
                    canApplyOracle("DELETE", target, sender, 0, "SERVICE"),
                    MessagingV2MutationAuthority.canApply("DELETE", target, sender, 0, "SERVICE")
                )
            }
        }
        // Every device x kind for the trusted-delete shape.
        for (device in MMA_DEVICE_POOL) {
            for (kind in MMA_KIND_POOL) {
                assertEquals(
                    canApplyOracle("DELETE", "alice", "system", device, kind),
                    MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", device, kind)
                )
            }
        }
        // One-unit edits of the four load-bearing literals.
        val edits = " _Xa0\u0000"
        for (target in listOf("EDIT", "DELETE", "system", "SERVICE")) {
            for (pos in target.indices) {
                for (rep in edits) {
                    val s = target.substring(0, pos) + rep + target.substring(pos + 1)
                    checkMmaEdited(target, s)
                }
                val s = target.removeRange(pos, pos + 1)
                checkMmaEdited(target, s)
                val flipped = target[pos].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
                val f = target.substring(0, pos) + flipped + target.substring(pos + 1)
                checkMmaEdited(target, f)
            }
            for (pos in 0..target.length) {
                for (ins in listOf('_', 'X')) {
                    val s = target.substring(0, pos) + ins + target.substring(pos)
                    checkMmaEdited(target, s)
                }
            }
        }
        // Every single low unit as each string field.
        for (code in 0x00 until 0x80) {
            val s = code.toChar().toString()
            assertEquals(
                canApplyOracle(s, "alice", "mallory", 1, "EVENT"),
                MessagingV2MutationAuthority.canApply(s, "alice", "mallory", 1, "EVENT")
            )
            assertEquals(
                canApplyOracle("DELETE", s, "system", 0, "SERVICE"),
                MessagingV2MutationAuthority.canApply("DELETE", s, "system", 0, "SERVICE")
            )
            assertEquals(
                canApplyOracle("DELETE", "alice", s, 0, "SERVICE"),
                MessagingV2MutationAuthority.canApply("DELETE", "alice", s, 0, "SERVICE")
            )
            assertEquals(
                canApplyOracle("DELETE", "alice", "system", 0, s),
                MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", 0, s)
            )
        }
    }

    private fun checkMmaAction(action: String) {
        // Author shape, stranger shape, and the three service-principal
        // shapes with one gate each broken.
        assertEquals(
            canApplyOracle(action, "alice", "alice", 1, "EVENT"),
            MessagingV2MutationAuthority.canApply(action, "alice", "alice", 1, "EVENT")
        )
        assertEquals(
            canApplyOracle(action, "alice", "mallory", 1, "EVENT"),
            MessagingV2MutationAuthority.canApply(action, "alice", "mallory", 1, "EVENT")
        )
        assertEquals(
            canApplyOracle(action, "alice", "system", 0, "SERVICE"),
            MessagingV2MutationAuthority.canApply(action, "alice", "system", 0, "SERVICE")
        )
        assertEquals(
            canApplyOracle(action, "alice", "system", 1, "SERVICE"),
            MessagingV2MutationAuthority.canApply(action, "alice", "system", 1, "SERVICE")
        )
        assertEquals(
            canApplyOracle(action, "alice", "system", 0, "EVENT"),
            MessagingV2MutationAuthority.canApply(action, "alice", "system", 0, "EVENT")
        )
    }

    private fun checkMmaEdited(original: String, edited: String) {
        // The edited literal is slotted into whichever field it belongs to;
        // the other fields stay at the trusted-delete shape.
        when (original) {
            "EDIT", "DELETE" -> assertEquals(
                canApplyOracle(edited, "alice", "system", 0, "SERVICE"),
                MessagingV2MutationAuthority.canApply(edited, "alice", "system", 0, "SERVICE")
            )
            "system" -> assertEquals(
                canApplyOracle("DELETE", "alice", edited, 0, "SERVICE"),
                MessagingV2MutationAuthority.canApply("DELETE", "alice", edited, 0, "SERVICE")
            )
            else -> assertEquals(
                canApplyOracle("DELETE", "alice", "system", 0, edited),
                MessagingV2MutationAuthority.canApply("DELETE", "alice", "system", 0, edited)
            )
        }
    }
}
