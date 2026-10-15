package com.maodouchat.server.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

internal enum class LifecycleState { RUNNING, STOPPED }

internal data class Runtime(
    val scope: CoroutineScope,
    val queue: Channel<suspend () -> Unit>
)

internal data class OutboxEntry(
    val id: String,
    val botId: String,
    val url: String,
    val tokenHash: String,
    val body: String,
    val ts: Long,
)

internal enum class DeliveryOutcome { DELIVERED, DEAD, SKIPPED }

internal class WebhookHttpException(
    val statusCode: Int,
    botId: String
) : Exception("webhook HTTP $statusCode for bot $botId")
