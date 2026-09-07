package com.maodouchat.server.service

/**
 * B09：通话信令 epoch / sequence 准入与投递排序（纯策略）。
 *
 * - 未设置（0/0）视为遗留客户端，始终放行。
 * - 同 call 同发送方：只接受不落后于已接受游标的信令。
 * - 投递排序：epoch → sequence → timestamp → id。
 */
object CallSignalingOrderPolicy {

    data class Cursor(val epoch: Long, val sequence: Long)

    sealed interface Admit {
        data object Accept : Admit
        data object RejectStale : Admit
    }

    fun admit(incoming: Cursor, lastAccepted: Cursor?): Admit {
        if (incoming.epoch == 0L && incoming.sequence == 0L) return Admit.Accept
        if (lastAccepted == null) return Admit.Accept
        return if (compare(incoming, lastAccepted) < 0) Admit.RejectStale else Admit.Accept
    }

    fun compare(a: Cursor, b: Cursor): Int {
        val byEpoch = a.epoch.compareTo(b.epoch)
        if (byEpoch != 0) return byEpoch
        return a.sequence.compareTo(b.sequence)
    }

    /** Stable delivery order: epoch, sequence, then wall-clock / id tie-breakers. */
    fun compareForDelivery(
        epochA: Long,
        sequenceA: Long,
        timestampA: Long,
        idA: String,
        epochB: Long,
        sequenceB: Long,
        timestampB: Long,
        idB: String,
    ): Int {
        val byCursor = compare(Cursor(epochA, sequenceA), Cursor(epochB, sequenceB))
        if (byCursor != 0) return byCursor
        val byTs = timestampA.compareTo(timestampB)
        if (byTs != 0) return byTs
        return idA.compareTo(idB)
    }
}
