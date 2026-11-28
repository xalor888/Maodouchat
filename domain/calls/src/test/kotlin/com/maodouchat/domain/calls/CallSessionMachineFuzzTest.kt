package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSessionMachineFuzzTest {

    private val random = Random(2026112245L)

    // 与生产侧逐行同构的模型机：begin 只在 IDLE/ENDED 且 peer 非空时推进一代；
    // 其余迁移只在代一致且当前阶段在允许集内时生效；isCurrent 排除 0 代与 ENDED。
    private class MachineModel {
        var epoch: Long = 0L
        var phase: Phase = Phase.IDLE
        var peerId: String = ""
        var incoming: Boolean = false

        fun snapshot(): Snapshot = Snapshot(epoch, phase, peerId, incoming)

        fun begin(peerId: String, phase: Phase, incoming: Boolean): Snapshot {
            check(peerId.isNotBlank()) { "Call peer is required" }
            check(this.phase == Phase.IDLE || this.phase == Phase.ENDED) { "Call session already active" }
            epoch += 1L
            this.phase = phase
            this.peerId = peerId
            this.incoming = incoming
            return snapshot()
        }

        fun transition(epoch: Long, allowed: Set<Phase>, transform: (Snapshot) -> Snapshot): Snapshot {
            if (this.epoch != epoch || this.phase !in allowed) return snapshot()
            val next = transform(snapshot())
            this.epoch = next.epoch
            this.phase = next.phase
            this.peerId = next.peerId
            this.incoming = next.incoming
            return snapshot()
        }

        fun isCurrent(epoch: Long): Boolean = epoch != 0L && this.epoch == epoch && phase != Phase.ENDED
    }

    private val peerPool = listOf("alice", "bob", "peer-1", "a")

    private fun randomEpoch(model: MachineModel): Long =
        when (random.nextInt(7)) {
            0 -> 0L
            1 -> 1L
            2 -> model.epoch
            3 -> model.epoch + 1L
            4 -> -1L
            5 -> Long.MAX_VALUE
            else -> random.nextLong()
        }

    private fun step(machine: CallSessionMachine, model: MachineModel, op: Int) {
        when (op) {
            0, 1 -> {
                val peer = peerPool.random(random)
                val phase = if (op == 0) Phase.OUTGOING_RINGING else Phase.INCOMING_RINGING
                val incoming = op == 1
                val prod = runCatching {
                    if (op == 0) machine.beginOutgoing(peer) else machine.beginIncoming(peer)
                }
                val expected = runCatching { model.begin(peer, phase, incoming) }
                assertEquals(expected.isSuccess, prod.isSuccess, "op=begin peer=$peer phase=$phase")
                if (expected.isSuccess) {
                    assertEquals(expected.getOrThrow(), prod.getOrThrow())
                } else {
                    assertTrue(prod.exceptionOrNull() is IllegalStateException)
                }
                assertEquals(model.snapshot(), machine.snapshot())
            }
            2 -> {
                val epoch = randomEpoch(model)
                val prod = machine.markConnecting(epoch)
                val expected = model.transition(epoch, setOf(Phase.OUTGOING_RINGING, Phase.INCOMING_RINGING)) {
                    it.copy(phase = Phase.CONNECTING)
                }
                assertEquals(expected, prod, "op=markConnecting epoch=$epoch")
                assertEquals(model.snapshot(), machine.snapshot())
            }
            3 -> {
                val epoch = randomEpoch(model)
                val prod = machine.markConnected(epoch)
                val expected = model.transition(
                    epoch,
                    setOf(Phase.OUTGOING_RINGING, Phase.INCOMING_RINGING, Phase.CONNECTING),
                ) { it.copy(phase = Phase.CONNECTED) }
                assertEquals(expected, prod, "op=markConnected epoch=$epoch")
                assertEquals(model.snapshot(), machine.snapshot())
            }
            4 -> {
                val epoch = randomEpoch(model)
                val prod = machine.beginEnding(epoch)
                val expected = model.transition(
                    epoch,
                    Phase.entries.filterNot { it == Phase.IDLE || it == Phase.ENDED || it == Phase.ENDING }.toSet(),
                ) { it.copy(phase = Phase.ENDING) }
                assertEquals(expected, prod, "op=beginEnding epoch=$epoch")
                assertEquals(model.snapshot(), machine.snapshot())
            }
            5 -> {
                val epoch = randomEpoch(model)
                val prod = machine.finish(epoch)
                val expected = model.transition(epoch, setOf(Phase.ENDING)) {
                    it.copy(phase = Phase.ENDED)
                }
                assertEquals(expected, prod, "op=finish epoch=$epoch")
                assertEquals(model.snapshot(), machine.snapshot())
            }
            else -> {
                val epoch = randomEpoch(model)
                assertEquals(
                    model.isCurrent(epoch),
                    machine.isCurrent(epoch),
                    "op=isCurrent epoch=$epoch snapshot=${model.snapshot()}",
                )
            }
        }
    }

    @Test
    fun `random operation sequences match the model machine`() {
        repeat(50) {
            val machine = CallSessionMachine()
            val model = MachineModel()
            repeat(20) { step(machine, model, random.nextInt(7)) }
        }
    }

    @Test
    fun `begin increments the epoch from one across sessions`() {
        val machine = CallSessionMachine()
        assertEquals(1L, machine.beginOutgoing("alice").epoch)
        assertEquals(Phase.OUTGOING_RINGING, machine.snapshot().phase)
        machine.beginEnding(1L)
        machine.finish(1L)
        assertEquals(Phase.ENDED, machine.snapshot().phase)
        val second = machine.beginIncoming("bob")
        assertEquals(2L, second.epoch)
        assertEquals(Phase.INCOMING_RINGING, second.phase)
        assertTrue(second.incoming)
        assertEquals("bob", second.peerId)
    }

    @Test
    fun `begin with a blank peer fails and leaves the machine idle`() {
        val machine = CallSessionMachine()
        assertFailsWith<IllegalStateException> { machine.beginOutgoing("") }
        assertFailsWith<IllegalStateException> { machine.beginIncoming("   ") }
        assertEquals(Snapshot(0L, Phase.IDLE, "", false), machine.snapshot())
    }

    @Test
    fun `begin while a session is active fails`() {
        val machine = CallSessionMachine()
        machine.beginOutgoing("alice")
        assertFailsWith<IllegalStateException> { machine.beginIncoming("bob") }
        assertFailsWith<IllegalStateException> { machine.beginOutgoing("carol") }
        assertEquals(Snapshot(1L, Phase.OUTGOING_RINGING, "alice", false), machine.snapshot())
    }

    @Test
    fun `transitions ignore stale epochs and wrong phases`() {
        val machine = CallSessionMachine()
        // IDLE 时所有迁移都是空操作。
        assertEquals(Snapshot(), machine.markConnecting(1L))
        assertEquals(Snapshot(), machine.markConnected(1L))
        assertEquals(Snapshot(), machine.beginEnding(1L))
        assertEquals(Snapshot(), machine.finish(1L))
        machine.beginOutgoing("alice")
        // 代不对的迁移是空操作。
        assertEquals(Snapshot(1L, Phase.OUTGOING_RINGING, "alice", false), machine.markConnecting(999L))
        // CONNECTED 不能直接 finish；必须先 beginEnding。
        machine.markConnected(1L)
        assertEquals(Snapshot(1L, Phase.CONNECTED, "alice", false), machine.finish(1L))
        machine.beginEnding(1L)
        assertEquals(Snapshot(1L, Phase.ENDING, "alice", false), machine.snapshot())
        // ENDED 后迁移全部是空操作。
        machine.finish(1L)
        assertEquals(Snapshot(1L, Phase.ENDED, "alice", false), machine.markConnecting(1L))
        assertEquals(Snapshot(1L, Phase.ENDED, "alice", false), machine.finish(1L))
    }

    @Test
    fun `isCurrent pins zero epoch and ended phase`() {
        val machine = CallSessionMachine()
        assertFalse(machine.isCurrent(0L))
        assertFalse(machine.isCurrent(1L))
        machine.beginOutgoing("alice")
        assertTrue(machine.isCurrent(1L))
        assertFalse(machine.isCurrent(0L))
        assertFalse(machine.isCurrent(2L))
        machine.beginEnding(1L)
        machine.finish(1L)
        assertFalse(machine.isCurrent(1L))
    }
}
