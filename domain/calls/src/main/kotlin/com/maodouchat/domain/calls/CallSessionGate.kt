package com.maodouchat.domain.calls

import java.util.concurrent.atomic.AtomicLong

/** Rejects native callbacks and asynchronous failures from a released call generation. */
class CallSessionGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(session: Long): Boolean =
        generation.compareAndSet(session, session + 1L)

    fun isCurrent(session: Long): Boolean = generation.get() == session
}
