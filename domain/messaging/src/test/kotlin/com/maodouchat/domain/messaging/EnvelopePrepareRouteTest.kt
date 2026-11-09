package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvelopePrepareRouteTest {

    @Test
    fun `group control kinds take the multi-recipient route`() {
        assertEquals(
            EnvelopePrepareRoute.GROUP_CONTROL,
            decideEnvelopePrepareRoute(isGroup = true, isGroupControlKind = true),
        )
    }

    @Test
    fun `ordinary group messages take the sender key route`() {
        assertEquals(
            EnvelopePrepareRoute.GROUP_SENDER_KEY,
            decideEnvelopePrepareRoute(isGroup = true, isGroupControlKind = false),
        )
    }

    @Test
    fun `direct messages never take the group control route`() {
        assertEquals(
            EnvelopePrepareRoute.DIRECT,
            decideEnvelopePrepareRoute(isGroup = false, isGroupControlKind = true),
        )
    }

    @Test
    fun `direct messages take the direct route`() {
        assertEquals(
            EnvelopePrepareRoute.DIRECT,
            decideEnvelopePrepareRoute(isGroup = false, isGroupControlKind = false),
        )
    }
}
