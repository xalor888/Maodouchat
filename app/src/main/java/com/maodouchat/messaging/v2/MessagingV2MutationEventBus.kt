package com.maodouchat.messaging.v2

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-scoped bridge from durable timeline projection to active UI mutation coordinators. */
internal class MessagingV2MutationEventBus {
    private val mutableEvents = MutableSharedFlow<MessagingV2AuthoritativeMutation>(
        extraBufferCapacity = 64,
    )
    val events = mutableEvents.asSharedFlow()

    suspend fun publish(mutation: MessagingV2AuthoritativeMutation) {
        mutableEvents.emit(mutation)
    }
}
