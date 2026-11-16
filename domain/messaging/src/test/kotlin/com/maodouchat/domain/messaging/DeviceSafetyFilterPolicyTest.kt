package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.IdentityTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSafetyFilterPolicyTest {

    private fun device(
        deviceId: Int,
        trustState: IdentityTrustState = IdentityTrustState.TRUSTED,
        safetyCode: String? = "123-456",
        identityKey: String = "key-aaa"
    ) = DeviceSafetyState(
        deviceId = deviceId,
        identityKey = identityKey,
        identityFingerprint = "fp-$deviceId",
        trustState = trustState,
        safetyCode = safetyCode
    )

    private val devices = listOf(
        device(11),
        device(22, trustState = IdentityTrustState.CHANGED, safetyCode = "999-000", identityKey = "key-bbb"),
        device(33, trustState = IdentityTrustState.VERIFIED, safetyCode = null, identityKey = "key-ccc"),
    )

    @Test
    fun `empty and blank query return all`() {
        assertEquals(devices, DeviceSafetyFilterPolicy.filter(devices, ""))
        assertEquals(devices, DeviceSafetyFilterPolicy.filter(devices, "   "))
    }

    @Test
    fun `matches by device id`() {
        assertTrue(DeviceSafetyFilterPolicy.matches(devices[0], "11"))
        assertFalse(DeviceSafetyFilterPolicy.matches(devices[0], "22"))
    }

    @Test
    fun `matches by trust state name case-insensitively`() {
        assertEquals(listOf(devices[1]), DeviceSafetyFilterPolicy.filter(devices, "changed"))
        assertEquals(listOf(devices[0]), DeviceSafetyFilterPolicy.filter(devices, "TRUSTED"))
    }

    @Test
    fun `matches by safety code`() {
        assertEquals(listOf(devices[1]), DeviceSafetyFilterPolicy.filter(devices, "999"))
    }

    @Test
    fun `matches by identity key and tolerates null safety code`() {
        assertEquals(listOf(devices[2]), DeviceSafetyFilterPolicy.filter(devices, "key-ccc"))
        assertFalse(DeviceSafetyFilterPolicy.matches(devices[2], "123"))
    }

    @Test
    fun `no match returns empty and query is trimmed`() {
        assertEquals(emptyList<DeviceSafetyState>(), DeviceSafetyFilterPolicy.filter(devices, "zzz"))
        assertEquals(listOf(devices[1]), DeviceSafetyFilterPolicy.filter(devices, "  999 "))
    }
}
