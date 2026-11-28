// MessageMutationTracker fuzz (149th block). Oracle reimplements the
// single-flight tracker as an independent state machine (generation
// counter + pending map + deleted/revoked sets). Ops run as sequences
// so ticket-forgery, stale-ticket and delete/revoke interleavings are
// covered; a forged ticket (wrong generation/kind/id) must never
// touch pending state.
package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------- oracle ----------

private class TrackerOracle149 {
    private var generation = 0L
    private val pending = mutableMapOf<String, MessageMutationTicket>()
    private val deleted = mutableSetOf<String>()
    private val revoked = mutableSetOf<String>()

    fun begin(messageId: String, kind: MessageMutationKind): MessageMutationTicket? {
        if (messageId.isBlank() || messageId in deleted || pending.containsKey(messageId)) return null
        return MessageMutationTicket(messageId, kind, ++generation).also { pending[messageId] = it }
    }

    fun shouldRollback(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return ticket.messageId !in deleted && ticket.messageId !in revoked
    }

    fun complete(ticket: MessageMutationTicket): Boolean {
        if (pending[ticket.messageId] != ticket) return false
        pending.remove(ticket.messageId)
        return true
    }

    fun observeAuthoritative(messageId: String, kind: MessageMutationKind) {
        pending.remove(messageId)
        when (kind) {
            MessageMutationKind.DELETE -> {
                deleted += messageId
                revoked -= messageId
            }
            MessageMutationKind.REVOKE -> if (messageId !in deleted) revoked += messageId
            MessageMutationKind.EDIT -> Unit
        }
    }

    fun shouldDrop(messageId: String): Boolean =
        messageId in deleted || pending[messageId]?.kind == MessageMutationKind.DELETE

    fun shouldRenderRevoked(messageId: String): Boolean =
        messageId in revoked || pending[messageId]?.kind == MessageMutationKind.REVOKE
}

// One op in a sequence. R/C carry an explicit ticket (possibly forged:
// wrong generation, kind or id) so stale-ticket paths are reachable.
private data class MmtOp149(
    val op: String, // B begin, R rollback, C complete, O observe, D shouldDrop, V shouldRenderRevoked
    val id: String,
    val kind: MessageMutationKind = MessageMutationKind.EDIT,
    val ticketGen: Long = 0L,
    val ticketKind: MessageMutationKind = MessageMutationKind.EDIT,
)

// Hex-encode ids exactly like the shared Parse149 codec (Probe149's
// encTicket149), so the oracle and probe ticket strings compare equal.
private fun encHex149(s: String): String {
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

private fun encTicketLocal149(t: MessageMutationTicket?): String =
    if (t == null) "N" else "T:" + encHex149(t.messageId) + ":" + t.kind.name + ":" + t.generation

private fun runOpsProduction149(tracker: MessageMutationTracker, ops: List<MmtOp149>): List<String> {
    val out = mutableListOf<String>()
    for (op in ops) {
        when (op.op) {
            "B" -> out += encTicketLocal149(tracker.begin(op.id, op.kind))
            "R" -> out += if (tracker.shouldRollback(MessageMutationTicket(op.id, op.ticketKind, op.ticketGen))) "B1" else "B0"
            "C" -> out += if (tracker.complete(MessageMutationTicket(op.id, op.ticketKind, op.ticketGen))) "B1" else "B0"
            "O" -> {
                tracker.observeAuthoritative(op.id, op.kind)
                out += "U"
            }
            "D" -> out += if (tracker.shouldDrop(op.id)) "B1" else "B0"
            "V" -> out += if (tracker.shouldRenderRevoked(op.id)) "B1" else "B0"
            else -> error("bad op ${op.op}")
        }
    }
    return out
}

private fun runOpsOracle149(tracker: TrackerOracle149, ops: List<MmtOp149>): List<String> {
    val out = mutableListOf<String>()
    for (op in ops) {
        when (op.op) {
            "B" -> out += encTicketLocal149(tracker.begin(op.id, op.kind))
            "R" -> out += if (tracker.shouldRollback(MessageMutationTicket(op.id, op.ticketKind, op.ticketGen))) "B1" else "B0"
            "C" -> out += if (tracker.complete(MessageMutationTicket(op.id, op.ticketKind, op.ticketGen))) "B1" else "B0"
            "O" -> {
                tracker.observeAuthoritative(op.id, op.kind)
                out += "U"
            }
            "D" -> out += if (tracker.shouldDrop(op.id)) "B1" else "B0"
            "V" -> out += if (tracker.shouldRenderRevoked(op.id)) "B1" else "B0"
            else -> error("bad op ${op.op}")
        }
    }
    return out
}

// ---------- generators ----------

private val MMT_ID_POOL149 = arrayOf("a", "b", "c", "", " ", "\t", "a b", "A", "aa")
private val KIND_POOL149 = arrayOf(MessageMutationKind.DELETE, MessageMutationKind.REVOKE, MessageMutationKind.EDIT)
private val GEN_POOL149 = longArrayOf(0L, 1L, 2L, 3L, 4L, 5L, Long.MAX_VALUE)

private fun randomOp149(rnd: Random): MmtOp149 {
    val id = MMT_ID_POOL149[rnd.nextInt(MMT_ID_POOL149.size)]
    return when (rnd.nextInt(6)) {
        0 -> MmtOp149("B", id, KIND_POOL149[rnd.nextInt(KIND_POOL149.size)])
        1 -> MmtOp149("R", id, ticketKind = KIND_POOL149[rnd.nextInt(KIND_POOL149.size)], ticketGen = GEN_POOL149[rnd.nextInt(GEN_POOL149.size)])
        2 -> MmtOp149("C", id, ticketKind = KIND_POOL149[rnd.nextInt(KIND_POOL149.size)], ticketGen = GEN_POOL149[rnd.nextInt(GEN_POOL149.size)])
        3 -> MmtOp149("O", id, KIND_POOL149[rnd.nextInt(KIND_POOL149.size)])
        4 -> MmtOp149("D", id)
        else -> MmtOp149("V", id)
    }
}

private fun randomOps149(rnd: Random): List<MmtOp149> {
    val n = rnd.nextInt(1, 13)
    return (0 until n).map { randomOp149(rnd) }
}

class MessageMutationTrackerFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val t = MessageMutationTracker()
        // Blank ids never begin.
        assertNull(t.begin("", MessageMutationKind.EDIT))
        assertNull(t.begin("   ", MessageMutationKind.EDIT))
        assertNull(t.begin("\t", MessageMutationKind.DELETE))
        val oracle = TrackerOracle149()
        assertNull(oracle.begin("", MessageMutationKind.EDIT))
        // First begin wins; second begin while pending returns null.
        val tk = t.begin("a", MessageMutationKind.EDIT)!!
        assertEquals(1L, tk.generation)
        assertEquals(MessageMutationKind.EDIT, tk.kind)
        assertNull(t.begin("a", MessageMutationKind.DELETE))
        assertTrue(t.shouldDrop("a") == false)
        assertTrue(t.shouldRenderRevoked("a") == false)
        // Forged tickets never touch pending state.
        assertFalse(t.shouldRollback(MessageMutationTicket("a", MessageMutationKind.EDIT, 2L)))
        assertFalse(t.complete(MessageMutationTicket("a", MessageMutationKind.EDIT, 2L)))
        assertFalse(t.shouldRollback(MessageMutationTicket("a", MessageMutationKind.DELETE, 1L)))
        assertFalse(t.shouldRollback(MessageMutationTicket("zzz", MessageMutationKind.EDIT, 1L)))
        assertFalse(t.complete(MessageMutationTicket("a", MessageMutationKind.EDIT, 0L)))
        // The real ticket still completes afterwards.
        assertTrue(t.complete(MessageMutationTicket("a", MessageMutationKind.EDIT, 1L)))
        assertFalse(t.complete(MessageMutationTicket("a", MessageMutationKind.EDIT, 1L)))
        // Rollback of a live ticket removes it and reports true.
        val tk2 = t.begin("b", MessageMutationKind.DELETE)!!
        assertEquals(2L, tk2.generation)
        assertTrue(t.shouldRollback(tk2))
        assertFalse(t.shouldRollback(tk2))
        // Pending DELETE drops, pending REVOKE renders revoked.
        val tk3 = t.begin("c", MessageMutationKind.DELETE)!!
        assertTrue(t.shouldDrop("c"))
        assertFalse(t.shouldRenderRevoked("c"))
        assertTrue(t.complete(tk3))
        assertFalse(t.shouldDrop("c"))
        val tk4 = t.begin("d", MessageMutationKind.REVOKE)!!
        assertFalse(t.shouldDrop("d"))
        assertTrue(t.shouldRenderRevoked("d"))
        assertTrue(t.shouldRollback(tk4))
        assertFalse(t.shouldRenderRevoked("d"))
        // Authoritative DELETE: terminal, blocks begin, rollback reports false.
        t.observeAuthoritative("e", MessageMutationKind.DELETE)
        assertTrue(t.shouldDrop("e"))
        assertFalse(t.shouldRenderRevoked("e"))
        assertNull(t.begin("e", MessageMutationKind.EDIT))
        val tk5 = MessageMutationTicket("e", MessageMutationKind.EDIT, 99L)
        assertFalse(t.shouldRollback(tk5))
        // Authoritative REVOKE then DELETE clears the revoked flag.
        t.observeAuthoritative("f", MessageMutationKind.REVOKE)
        assertTrue(t.shouldRenderRevoked("f"))
        t.observeAuthoritative("f", MessageMutationKind.DELETE)
        assertTrue(t.shouldDrop("f"))
        assertFalse(t.shouldRenderRevoked("f"))
        assertNull(t.begin("f", MessageMutationKind.EDIT))
        // Authoritative DELETE then REVOKE does not resurrect revoked.
        t.observeAuthoritative("g", MessageMutationKind.DELETE)
        t.observeAuthoritative("g", MessageMutationKind.REVOKE)
        assertFalse(t.shouldRenderRevoked("g"))
        assertTrue(t.shouldDrop("g"))
        // Authoritative EDIT only clears pending, changes nothing else.
        val tk6 = t.begin("h", MessageMutationKind.EDIT)!!
        t.observeAuthoritative("h", MessageMutationKind.EDIT)
        assertFalse(t.shouldRollback(tk6))
        assertFalse(t.shouldDrop("h"))
        assertFalse(t.shouldRenderRevoked("h"))
        // Rollback after authoritative DELETE reports false (already removed).
        val tk7 = t.begin("i", MessageMutationKind.EDIT)!!
        t.observeAuthoritative("i", MessageMutationKind.DELETE)
        assertFalse(t.shouldRollback(tk7))
        // Rollback after authoritative REVOKE reports false (revoked set).
        val tk8 = t.begin("j", MessageMutationKind.EDIT)!!
        t.observeAuthoritative("j", MessageMutationKind.REVOKE)
        assertFalse(t.shouldRollback(tk8))
        assertTrue(t.shouldRenderRevoked("j"))
        // Full-sequence oracle agreement on the pinned script.
        val script = listOf(
            MmtOp149("B", "a", MessageMutationKind.EDIT),
            MmtOp149("B", "a", MessageMutationKind.DELETE),
            MmtOp149("R", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 2L),
            MmtOp149("C", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 1L),
            MmtOp149("B", "b", MessageMutationKind.DELETE),
            MmtOp149("D", "b"),
            MmtOp149("O", "b", MessageMutationKind.DELETE),
            MmtOp149("D", "b"),
            MmtOp149("B", "b", MessageMutationKind.EDIT),
            MmtOp149("O", "c", MessageMutationKind.REVOKE),
            MmtOp149("V", "c"),
            MmtOp149("O", "c", MessageMutationKind.DELETE),
            MmtOp149("V", "c"),
            MmtOp149("D", "c"),
        )
        assertEquals(
            runOpsOracle149(TrackerOracle149(), script),
            runOpsProduction149(MessageMutationTracker(), script),
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(149003)
        repeat(1500) {
            val ops = randomOps149(rnd)
            assertEquals(
                runOpsOracle149(TrackerOracle149(), ops),
                runOpsProduction149(MessageMutationTracker(), ops),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // All single ops over the id/kind/gen pools.
        val singles = mutableListOf<MmtOp149>()
        for (id in MMT_ID_POOL149) {
            for (kind in KIND_POOL149) {
                singles += MmtOp149("B", id, kind)
                singles += MmtOp149("O", id, kind)
                for (gen in GEN_POOL149) {
                    singles += MmtOp149("R", id, ticketKind = kind, ticketGen = gen)
                    singles += MmtOp149("C", id, ticketKind = kind, ticketGen = gen)
                }
            }
            singles += MmtOp149("D", id)
            singles += MmtOp149("V", id)
        }
        for (op in singles) {
            val seq = listOf(op)
            assertEquals(
                runOpsOracle149(TrackerOracle149(), seq),
                runOpsProduction149(MessageMutationTracker(), seq),
            )
        }
        // All ordered pairs over a compact op template set.
        val templates = listOf(
            MmtOp149("B", "a", MessageMutationKind.EDIT),
            MmtOp149("B", "a", MessageMutationKind.DELETE),
            MmtOp149("B", "b", MessageMutationKind.REVOKE),
            MmtOp149("R", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 1L),
            MmtOp149("R", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 2L),
            MmtOp149("C", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 1L),
            MmtOp149("O", "a", MessageMutationKind.DELETE),
            MmtOp149("O", "a", MessageMutationKind.REVOKE),
            MmtOp149("O", "b", MessageMutationKind.DELETE),
            MmtOp149("D", "a"),
            MmtOp149("V", "a"),
            MmtOp149("D", "b"),
            MmtOp149("V", "b"),
        )
        for (first in templates) {
            for (second in templates) {
                val seq = listOf(first, second)
                assertEquals(
                    runOpsOracle149(TrackerOracle149(), seq),
                    runOpsProduction149(MessageMutationTracker(), seq),
                )
            }
        }
        // All ordered triples over a smaller template set (state interleavings).
        val small = listOf(
            MmtOp149("B", "a", MessageMutationKind.EDIT),
            MmtOp149("B", "a", MessageMutationKind.DELETE),
            MmtOp149("R", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 1L),
            MmtOp149("C", "a", ticketKind = MessageMutationKind.EDIT, ticketGen = 1L),
            MmtOp149("O", "a", MessageMutationKind.DELETE),
            MmtOp149("O", "a", MessageMutationKind.REVOKE),
            MmtOp149("D", "a"),
            MmtOp149("V", "a"),
        )
        for (x in small) {
            for (y in small) {
                for (z in small) {
                    val seq = listOf(x, y, z)
                    assertEquals(
                        runOpsOracle149(TrackerOracle149(), seq),
                        runOpsProduction149(MessageMutationTracker(), seq),
                    )
                }
            }
        }
    }
}
