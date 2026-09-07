package com.maodouchat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupPermissionPolicyTest {

    @Test
    fun belowTiramisuRequestsNothing() {
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 32,
            isGranted = { false },
        )
        assertTrue(needed.isEmpty())
    }

    @Test
    fun tiramisuRequestsNotificationsWhenDenied() {
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = StartupPermissionPolicy.TIRAMISU_SDK,
            isGranted = { false },
        )
        assertEquals(listOf(StartupPermissionPolicy.POST_NOTIFICATIONS), needed)
    }

    @Test
    fun tiramisuSkipsWhenAlreadyGranted() {
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 34,
            isGranted = { it == StartupPermissionPolicy.POST_NOTIFICATIONS },
        )
        assertTrue(needed.isEmpty())
    }
}
