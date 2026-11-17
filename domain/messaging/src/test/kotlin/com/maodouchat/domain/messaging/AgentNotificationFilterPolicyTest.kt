package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentNotificationFilterPolicyTest {

    @Test
    fun `isVisible passes unblocked items`() {
        assertTrue(
            AgentNotificationFilterPolicy.isVisible("", "", "k1", null, emptySet(), emptySet()),
        )
        assertTrue(
            AgentNotificationFilterPolicy.isVisible("c1", "u1", "k1", "maodou://x", setOf("c2"), setOf("u2")),
        )
    }

    @Test
    fun `isVisible hides blocked chat and secret caller`() {
        assertFalse(
            AgentNotificationFilterPolicy.isVisible("c1", "u1", "k1", null, setOf("c1"), emptySet()),
        )
        assertFalse(
            AgentNotificationFilterPolicy.isVisible("c1", "u1", "k1", null, emptySet(), setOf("u1")),
        )
        assertTrue(
            AgentNotificationFilterPolicy.isVisible("", "u1", "k1", null, setOf("c1"), setOf("u2")),
        )
        assertFalse(
            AgentNotificationFilterPolicy.isVisible("", "u1", "k1", null, setOf("c1"), setOf("u1")),
        )
    }

    @Test
    fun `isVisible hides when merge key or deeplink mentions a blocked id`() {
        assertFalse(
            AgentNotificationFilterPolicy.isVisible("c9", "u1", "merge:c1", null, setOf("c1"), emptySet()),
        )
        assertFalse(
            AgentNotificationFilterPolicy.isVisible("c9", "u1", "k1", "maodou://chat/c1", setOf("c1"), emptySet()),
        )
    }

    @Test
    fun `notificationsLimit clamps to 1 to 40`() {
        assertEquals(1, AgentNotificationFilterPolicy.notificationsLimit(0))
        assertEquals(1, AgentNotificationFilterPolicy.notificationsLimit(1))
        assertEquals(40, AgentNotificationFilterPolicy.notificationsLimit(40))
        assertEquals(40, AgentNotificationFilterPolicy.notificationsLimit(41))
    }
}
