package com.maodouchat.domain.messaging

import java.util.concurrent.atomic.AtomicLong

/** Rejects callbacks from cancelled or superseded AI requests. */
class AiRequestGenerationGate {
    private val generation = AtomicLong(0L)

    fun next(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = generation.get() == token
}
