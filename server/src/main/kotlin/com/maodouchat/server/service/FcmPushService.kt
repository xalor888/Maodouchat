package com.maodouchat.server.service

import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRepository
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Offline wake sender — FCM HTTP v1 has been removed.
 *
 * Real-time delivery is WebSocket `/ws` plus the Ideaura-style client keep-alive
 * (foreground service, daemon resurrection, optional hold-call). Enqueue methods
 * stay as call-site no-ops so routing metadata contracts remain testable without
 * talking to Google.
 *
 * 实现已按域拆出：[FcmPushEnqueue.kt]（入队）、[FcmPushDeliveryPolicy.kt]（投递策略）、
 * [FcmPushCrypto.kt]（载荷签名）。
 */
class FcmPushService(
    @Suppress("unused") private val pushTokenRepository: PushTokenRepository,
    @Suppress("unused") private val preferenceRepository: NotificationPreferenceRepository,
    @Suppress("unused") private val projectId: String = "",
    @Suppress("unused") private val serviceAccountFile: String = "",
    @Suppress("unused") private val nowMillis: () -> Long = { System.currentTimeMillis() },
    @Suppress("unused") private val retryDelayMs: Long = 1_000L,
    @Suppress("unused") private val startWorkers: Boolean = true
) {
    internal val logger = LoggerFactory.getLogger(FcmPushService::class.java)
    internal val closed = AtomicBoolean(false)
    internal var onQueued: ((Delivery) -> Unit)? = null

    /** FCM is gone; never treat this as a live remote-push channel. */
    val isConfigured: Boolean
        get() = false

    internal data class Delivery(
        val recipientId: String,
        val isCall: Boolean,
        val data: Map<String, String>,
        val breakthroughDnd: Boolean = false
    )
}
