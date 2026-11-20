package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.test.Test
import kotlin.test.assertEquals

class CallPermissionStringsTest {

    @Test
    fun audioRequiresOnlyMicrophone() {
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            CallPermissionStrings.requiredPermissions(CallType.AUDIO).toList()
        )
    }

    @Test
    fun groupRequiresOnlyMicrophone() {
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            CallPermissionStrings.requiredPermissions(CallType.GROUP).toList()
        )
    }

    @Test
    fun videoRequiresMicrophoneAndCamera() {
        assertEquals(
            listOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"),
            CallPermissionStrings.requiredPermissions(CallType.VIDEO).toList()
        )
    }

    @Test
    fun constantsMatchManifestValues() {
        assertEquals("android.permission.RECORD_AUDIO", CallPermissionStrings.RECORD_AUDIO)
        assertEquals("android.permission.CAMERA", CallPermissionStrings.CAMERA)
    }
}
