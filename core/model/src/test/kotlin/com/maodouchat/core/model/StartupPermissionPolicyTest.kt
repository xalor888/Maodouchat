package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPermissionPolicyTest {

    @Test
    fun `below tiramisu requests nothing`() {
        assertTrue(
            StartupPermissionPolicy.permissionsToRequest(sdkInt = 32, isGranted = { false }).isEmpty()
        )
    }

    @Test
    fun `tiramisu requests notification permission when denied`() {
        assertEquals(
            listOf(StartupPermissionPolicy.POST_NOTIFICATIONS),
            StartupPermissionPolicy.permissionsToRequest(
                sdkInt = StartupPermissionPolicy.TIRAMISU_SDK,
                isGranted = { false },
            ),
        )
    }
}
