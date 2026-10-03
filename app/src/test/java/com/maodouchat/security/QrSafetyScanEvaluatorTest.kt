package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrSafetyScanEvaluatorTest {

    private class FakeSignal(
        private val deviceIdValue: Int = 7,
        private val localFingerprint: String = "AA:BB:CC",
        private val remoteFingerprint: String? = "DD:EE:FF",
        private val safetyCodeValue: String? = "123-456",
    ) : SafetySignalPort {
        var deviceIdCalls = 0
            private set

        override fun deviceId(): Int {
            deviceIdCalls += 1
            return deviceIdValue
        }

        override fun localIdentityFingerprint(): String = localFingerprint

        override fun remoteIdentityFingerprint(remoteUserId: String, deviceId: Int): String? =
            remoteFingerprint

        override fun safetyCode(remoteUserId: String, deviceId: Int): String? = safetyCodeValue
    }

    private fun request(
        ownerUserId: String = "u-owner",
        ownerDeviceId: Int = 3,
        peerUserId: String = "u-me",
        peerDeviceId: Int = 7,
        ownerFingerprint: String? = "DD:EE:FF",
        peerFingerprint: String? = "AA:BB:CC",
        safetyCode: String? = "123-456",
    ) = SafetyScanRequest(
        ownerUserId = ownerUserId,
        ownerDeviceId = ownerDeviceId,
        peerUserId = peerUserId,
        peerDeviceId = peerDeviceId,
        ownerIdentityFingerprint = ownerFingerprint,
        peerIdentityFingerprint = peerFingerprint,
        safetyCode = safetyCode,
    )

    @Test
    fun blankSessionExpiresBeforeAnythingElse() {
        val fake = FakeSignal()
        val status = QrSafetyScanEvaluator.evaluate(request(), currentUserId = "", signal = fake)
        assertEquals(SafetyScanStatus.SESSION_EXPIRED, status)
        assertTrue("即使会话过期，原实现也会先读一次本机 deviceId", fake.deviceIdCalls >= 1)
    }

    @Test
    fun scanningAnotherAccountsQrIsWrongAccount() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(peerUserId = "u-other"),
            currentUserId = "u-me",
            signal = FakeSignal(),
        )
        assertEquals(SafetyScanStatus.WRONG_ACCOUNT, status)
    }

    @Test
    fun scanningAnotherDeviceOfMineIsWrongDevice() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(peerDeviceId = 9),
            currentUserId = "u-me",
            signal = FakeSignal(deviceIdValue = 7),
        )
        assertEquals(SafetyScanStatus.WRONG_DEVICE, status)
    }

    @Test
    fun fingerprintPathWithoutRemoteFingerprintIsNoSession() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(),
            currentUserId = "u-me",
            signal = FakeSignal(remoteFingerprint = null),
        )
        assertEquals(SafetyScanStatus.NO_SESSION, status)
    }

    @Test
    fun peerFingerprintMismatchIsWrongDeviceQr() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(peerFingerprint = "FF:FF:FF"),
            currentUserId = "u-me",
            signal = FakeSignal(localFingerprint = "AA-BB-CC", remoteFingerprint = "DD-EE-FF"),
        )
        assertEquals(SafetyScanStatus.WRONG_DEVICE_QR, status)
    }

    @Test
    fun matchingFingerprintsAreNormalizedBeforeCompare() {
        val status = QrSafetyScanEvaluator.evaluate(
            // 本机/QR 两侧的指纹只差分隔符与大小写 → 归一化后必须判 MATCH。
            request(ownerFingerprint = "dd:ee:ff"),
            currentUserId = "u-me",
            signal = FakeSignal(localFingerprint = "AA:BB:CC", remoteFingerprint = "DD EE FF"),
        )
        assertEquals(SafetyScanStatus.FINGERPRINT_MATCH, status)
    }

    @Test
    fun nonMatchingOwnerFingerprintIsMismatch() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(ownerFingerprint = "11:22:33"),
            currentUserId = "u-me",
            signal = FakeSignal(localFingerprint = "AA:BB:CC", remoteFingerprint = "DD:EE:FF"),
        )
        assertEquals(SafetyScanStatus.FINGERPRINT_MISMATCH, status)
    }

    @Test
    fun codePathWithoutLocalCodeIsNoSession() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(ownerFingerprint = null, peerFingerprint = null),
            currentUserId = "u-me",
            signal = FakeSignal(safetyCodeValue = null),
        )
        assertEquals(SafetyScanStatus.NO_SESSION, status)
    }

    @Test
    fun blankCodeInQrIsInvalidCode() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(ownerFingerprint = null, peerFingerprint = null, safetyCode = ""),
            currentUserId = "u-me",
            signal = FakeSignal(safetyCodeValue = "123456"),
        )
        assertEquals(SafetyScanStatus.INVALID_CODE, status)
    }

    @Test
    fun matchingCodesAreNormalizedToDigits() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(ownerFingerprint = null, peerFingerprint = null, safetyCode = "123 456"),
            currentUserId = "u-me",
            signal = FakeSignal(safetyCodeValue = "123-456"),
        )
        assertEquals(SafetyScanStatus.CODE_MATCH, status)
    }

    @Test
    fun differentCodesAreMismatch() {
        val status = QrSafetyScanEvaluator.evaluate(
            request(ownerFingerprint = null, peerFingerprint = null, safetyCode = "999999"),
            currentUserId = "u-me",
            signal = FakeSignal(safetyCodeValue = "123-456"),
        )
        assertEquals(SafetyScanStatus.CODE_MISMATCH, status)
    }
}
