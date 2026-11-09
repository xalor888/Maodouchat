package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageStatus
import com.maodouchat.domain.messaging.TimelineProjectionRoute.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineProjectionRouteTest {

    @Test
    fun `sender key request type routes to sender key branch`() {
        assertEquals(
            Route.SENDER_KEY_REQUEST,
            TimelineProjectionRoute.decide(TYPE_SENDER_KEY_REQUEST, hasEvent = true),
        )
    }

    @Test
    fun `event content routes to event branch`() {
        assertEquals(
            Route.EVENT,
            TimelineProjectionRoute.decide("EVENT", hasEvent = true),
        )
    }

    @Test
    fun `plain content routes to data branch`() {
        assertEquals(
            Route.DATA,
            TimelineProjectionRoute.decide("TEXT", hasEvent = false),
        )
    }

    @Test
    fun `own message projects as sent`() {
        assertEquals(
            MessageStatus.SENT,
            TimelineProjectionRoute.projectedStatus("owner-1", "owner-1"),
        )
    }

    @Test
    fun `peer message projects as delivered`() {
        assertEquals(
            MessageStatus.DELIVERED,
            TimelineProjectionRoute.projectedStatus("peer-1", "owner-1"),
        )
    }
}
