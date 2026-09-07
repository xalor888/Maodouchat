package com.maodouchat.call

/**
 * P03 / B09：客户端通话信令 epoch / sequence 准入（与服务端 [CallSignalingOrderPolicy] 同构）。
 *
 * - 未设置（0/0）视为遗留客户端，始终放行。
 * - 同 call 同发送方：只接受不落后于已接受游标的信令。
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
}
