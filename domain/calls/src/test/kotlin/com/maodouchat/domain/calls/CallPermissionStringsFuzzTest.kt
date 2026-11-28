package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallPermissionStringsFuzzTest {

    private fun expectedPermissions(callType: CallType): List<String> =
        CallReliabilityPolicy.requiredPermissions(callType).map { permission ->
            when (permission) {
                CallMediaPermission.MICROPHONE -> CallPermissionStrings.RECORD_AUDIO
                CallMediaPermission.CAMERA -> CallPermissionStrings.CAMERA
            }
        }

    @Test
    fun `requiredPermissions matches the reliability policy for every call type`() {
        for (callType in CallType.entries) {
            assertEquals(
                expectedPermissions(callType),
                CallPermissionStrings.requiredPermissions(callType).toList(),
                "callType=$callType",
            )
        }
    }

    @Test
    fun `video requires microphone then camera, audio and group only microphone`() {
        assertEquals(
            listOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"),
            CallPermissionStrings.requiredPermissions(CallType.VIDEO).toList(),
        )
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            CallPermissionStrings.requiredPermissions(CallType.AUDIO).toList(),
        )
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            CallPermissionStrings.requiredPermissions(CallType.GROUP).toList(),
        )
    }

    @Test
    fun `constants are the exact manifest values`() {
        assertEquals("android.permission.RECORD_AUDIO", CallPermissionStrings.RECORD_AUDIO)
        assertEquals("android.permission.CAMERA", CallPermissionStrings.CAMERA)
        for (callType in CallType.entries) {
            for (permission in CallPermissionStrings.requiredPermissions(callType)) {
                assertTrue(
                    permission.startsWith("android.permission."),
                    "callType=$callType permission=$permission",
                )
            }
        }
    }

    @Test
    fun `every call returns a fresh array with no duplicates`() {
        for (callType in CallType.entries) {
            val first = CallPermissionStrings.requiredPermissions(callType)
            val second = CallPermissionStrings.requiredPermissions(callType)
            assertFalse(first === second, "callType=$callType")
            assertEquals(first.toList(), second.toList())
            assertEquals(first.size, first.toSet().size)
        }
    }
}
