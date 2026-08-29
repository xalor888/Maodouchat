package com.maodouchat.call

class CallSessionMachine {
    enum class Phase {
        IDLE,
        OUTGOING_RINGING,
        INCOMING_RINGING,
        CONNECTING,
        CONNECTED,
        ENDING,
        ENDED,
    }

    data class Snapshot(
        val epoch: Long = 0L,
        val phase: Phase = Phase.IDLE,
        val peerId: String = "",
        val incoming: Boolean = false,
    )

    private var current = Snapshot()

    fun snapshot(): Snapshot = current

    fun beginOutgoing(peerId: String): Snapshot = begin(peerId, Phase.OUTGOING_RINGING, incoming = false)

    fun beginIncoming(peerId: String): Snapshot = begin(peerId, Phase.INCOMING_RINGING, incoming = true)

    fun markConnecting(epoch: Long): Snapshot = transition(epoch, setOf(Phase.OUTGOING_RINGING, Phase.INCOMING_RINGING)) {
        it.copy(phase = Phase.CONNECTING)
    }

    fun markConnected(epoch: Long): Snapshot = transition(
        epoch,
        setOf(Phase.OUTGOING_RINGING, Phase.INCOMING_RINGING, Phase.CONNECTING),
    ) { it.copy(phase = Phase.CONNECTED) }

    fun beginEnding(epoch: Long): Snapshot = transition(
        epoch,
        Phase.entries.filterNot { it == Phase.IDLE || it == Phase.ENDED || it == Phase.ENDING }.toSet(),
    ) { it.copy(phase = Phase.ENDING) }

    fun finish(epoch: Long): Snapshot = transition(epoch, setOf(Phase.ENDING)) {
        it.copy(phase = Phase.ENDED)
    }

    fun isCurrent(epoch: Long): Boolean = epoch != 0L && current.epoch == epoch && current.phase != Phase.ENDED

    private fun begin(peerId: String, phase: Phase, incoming: Boolean): Snapshot {
        check(peerId.isNotBlank()) { "Call peer is required" }
        check(current.phase == Phase.IDLE || current.phase == Phase.ENDED) { "Call session already active" }
        current = Snapshot(current.epoch + 1L, phase, peerId, incoming)
        return current
    }

    private inline fun transition(
        epoch: Long,
        allowed: Set<Phase>,
        transform: (Snapshot) -> Snapshot,
    ): Snapshot {
        if (current.epoch != epoch || current.phase !in allowed) return current
        current = transform(current)
        return current
    }
}
