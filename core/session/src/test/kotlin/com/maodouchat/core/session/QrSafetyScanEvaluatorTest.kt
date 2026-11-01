package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class QrSafetyScanEvaluatorTest {

    private class FakeSignal(
        private val localFingerprint: String = "ab cd",
        private val ownerFingerprint: String? = "ef12",
        private val code: String? = "123456",
    ) : SafetySignalPort {
        override fun deviceId(): Int = 7
        override fun localIdentityFingerprint(): String = localFingerprint
        override fun remoteIdentityFingerprint(remoteUserId: String, deviceId: Int): String? = ownerFingerprint
        override fun safetyCode(remoteUserId: String, deviceId: Int): String? = code
    }

    private fun request(
        peerUserId: String = "u1",
        peerDeviceId: Int = 7,
        ownerFingerprint: String? = "EF12",
        peerFingerprint: String? = "ab-cd",
        code: String? = null,
    ) = SafetyScanRequest(
        ownerUserId = "u0",
        ownerDeviceId = 3,
        peerUserId = peerUserId,
        peerDeviceId = peerDeviceId,
        ownerIdentityFingerprint = ownerFingerprint,
        peerIdentityFingerprint = peerFingerprint,
        safetyCode = code,
    )

    @Test
    fun `blank current user fails closed as session expired`() {
        assertEquals(
            SafetyScanStatus.SESSION_EXPIRED,
            QrSafetyScanEvaluator.evaluate(request(), "", FakeSignal()),
        )
    }

    @Test
    fun `wrong account and wrong device fail closed`() {
        assertEquals(
            SafetyScanStatus.WRONG_ACCOUNT,
            QrSafetyScanEvaluator.evaluate(request(peerUserId = "u2"), "u1", FakeSignal()),
        )
        assertEquals(
            SafetyScanStatus.WRONG_DEVICE,
            QrSafetyScanEvaluator.evaluate(request(peerDeviceId = 8), "u1", FakeSignal()),
        )
    }

    @Test
    fun `normalized fingerprints match despite separators and case`() {
        assertEquals(
            SafetyScanStatus.FINGERPRINT_MATCH,
            QrSafetyScanEvaluator.evaluate(request(), "u1", FakeSignal()),
        )
    }

    @Test
    fun `fingerprint mismatch is reported`() {
        // 属主指纹错配才判 FINGERPRINT_MISMATCH；对端指纹错配先判 WRONG_DEVICE_QR（见原 app 侧用例）。
        assertEquals(
            SafetyScanStatus.FINGERPRINT_MISMATCH,
            QrSafetyScanEvaluator.evaluate(request(ownerFingerprint = "zz99"), "u1", FakeSignal()),
        )
    }

    @Test
    fun `blank fingerprints fall back to safety code comparison`() {
        assertEquals(
            SafetyScanStatus.CODE_MATCH,
            QrSafetyScanEvaluator.evaluate(
                request(ownerFingerprint = null, peerFingerprint = null, code = "123456"),
                "u1", FakeSignal(),
            ),
        )
        assertEquals(
            SafetyScanStatus.CODE_MISMATCH,
            QrSafetyScanEvaluator.evaluate(
                request(ownerFingerprint = null, peerFingerprint = null, code = "654321"),
                "u1", FakeSignal(),
            ),
        )
    }
}
