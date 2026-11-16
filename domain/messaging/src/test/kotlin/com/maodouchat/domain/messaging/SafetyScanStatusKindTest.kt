package com.maodouchat.domain.messaging

import com.maodouchat.core.session.SafetyScanStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SafetyScanStatusKindTest {

    private val peerDeviceId = 7
    private val ownerDeviceId = 3

    private fun kind(status: SafetyScanStatus) =
        safetyScanStatusKind(status, peerDeviceId, ownerDeviceId)

    @Test
    fun `wrong device carries the peer device id`() {
        val (kind, arg) = kind(SafetyScanStatus.WRONG_DEVICE)
        assertEquals(SafetyScanStatusKind.WRONG_DEVICE, kind)
        assertEquals(peerDeviceId, arg)
    }

    @Test
    fun `match statuses carry the owner device id`() {
        val (fingerprintKind, fingerprintArg) = kind(SafetyScanStatus.FINGERPRINT_MATCH)
        assertEquals(SafetyScanStatusKind.FINGERPRINT_MATCH, fingerprintKind)
        assertEquals(ownerDeviceId, fingerprintArg)
        val (codeKind, codeArg) = kind(SafetyScanStatus.CODE_MATCH)
        assertEquals(SafetyScanStatusKind.CODE_MATCH, codeKind)
        assertEquals(ownerDeviceId, codeArg)
    }

    @Test
    fun `the other seven statuses carry no device id`() {
        val cases = mapOf(
            SafetyScanStatus.SESSION_EXPIRED to SafetyScanStatusKind.SESSION_EXPIRED,
            SafetyScanStatus.WRONG_ACCOUNT to SafetyScanStatusKind.WRONG_ACCOUNT,
            SafetyScanStatus.NO_SESSION to SafetyScanStatusKind.NO_SESSION,
            SafetyScanStatus.WRONG_DEVICE_QR to SafetyScanStatusKind.WRONG_DEVICE_QR,
            SafetyScanStatus.FINGERPRINT_MISMATCH to SafetyScanStatusKind.FINGERPRINT_MISMATCH,
            SafetyScanStatus.INVALID_CODE to SafetyScanStatusKind.INVALID_CODE,
            SafetyScanStatus.CODE_MISMATCH to SafetyScanStatusKind.CODE_MISMATCH,
        )
        cases.forEach { (status, expected) ->
            val (kind, arg) = kind(status)
            assertEquals(expected, kind, status.name)
            assertNull(arg, status.name)
        }
    }
}
