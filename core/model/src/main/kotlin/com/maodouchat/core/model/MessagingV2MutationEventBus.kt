package com.maodouchat.core.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-scoped bridge from durable timeline projection to active UI mutation coordinators. */
class MessagingV2MutationEventBus {
    private val mutableEvents = MutableSharedFlow<MessagingV2AuthoritativeMutation>(
        extraBufferCapacity = 64,
    )
    val events = mutableEvents.asSharedFlow()

    suspend fun publish(mutation: MessagingV2AuthoritativeMutation) {
        mutableEvents.emit(mutation)
    }
}
