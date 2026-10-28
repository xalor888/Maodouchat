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
    @Test
    fun belowTiramisuNeverConsultsGrantState() {
        var calls = 0
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 32,
            isGranted = { calls++; true },
        )
        assertTrue(needed.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun aboveTiramisuRequestsWhenDenied() {
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 35,
            isGranted = { false },
        )
        assertEquals(listOf(StartupPermissionPolicy.POST_NOTIFICATIONS), needed)
    }

    @Test
    fun constantsAreExact() {
        assertEquals(33, StartupPermissionPolicy.TIRAMISU_SDK)
        assertEquals(
            "android.permission.POST_NOTIFICATIONS",
            StartupPermissionPolicy.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun grantPredicateReceivesExactlyTheNotificationPermission() {
        val seen = mutableListOf<String>()
        var calls = 0
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 33,
            isGranted = { seen.add(it); calls++; false },
        )
        assertEquals(listOf(StartupPermissionPolicy.POST_NOTIFICATIONS), needed)
        assertEquals(listOf(StartupPermissionPolicy.POST_NOTIFICATIONS), seen)
        assertEquals(1, calls)
    }

    @Test
    fun zeroSdkRequestsNothing() {
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = 0,
            isGranted = { false },
        )
        assertTrue(needed.isEmpty())
    }

}
