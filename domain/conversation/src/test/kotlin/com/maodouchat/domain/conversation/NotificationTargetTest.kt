package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AppLinkDestination

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationTargetTest {

    @Test
    fun groupInviteTargetMapsToDestination() {
        val target = NotificationTarget.GroupInvite(
            code = "B".repeat(40),
            sessionGeneration = 1L,
            ownerUserId = "u1",
        )
        val dest = target.toDestination() as AppLinkDestination.GroupInvite
        assertEquals("B".repeat(40), dest.code)
        assertEquals("join_group_invite/${"B".repeat(40)}", dest.toRoute())
    }
}
