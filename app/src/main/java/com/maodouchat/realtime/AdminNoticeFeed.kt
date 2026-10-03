package com.maodouchat.realtime

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object AdminNoticeFeed {

    fun visibleNotices(): Flow<RealtimeDomainEvent.AdminNotice> = flow {
        val ownerUserId = CurrentSession.ownerUserId()
        MaodouchatApp.instance.realtimeEventDispatcher.adminNoticeEvents.collect { event ->
            if (ownerUserId.isBlank() || !BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)) {
                return@collect
            }
            if (event.text.trim().isBlank()) return@collect
            emit(event)
        }
    }
}
