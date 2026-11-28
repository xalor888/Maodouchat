// Fuzz / random-oracle tests for QrSafetyScanEvaluator
// (checklist line 931, 153rd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: blank current
// user / wrong account / wrong device fail closed, then fingerprints
// (letters+digits only, lowercased) decide match/mismatch, else the
// safety code (digits only) decides. The fake signal returns canned values.
package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun String.normalizedFingerprintOracle153(): String =
    filter { it.isLetterOrDigit() }.lowercase()

internal fun String.normalizedSafetyCodeOracle153(): String =
    filter { it.isDigit() }

internal fun evaluateOracle153(
    request: SafetyScanRequest,
    currentUserId: String,
    signal: SafetySignalPort,
): SafetyScanStatus {
    val currentDeviceId = signal.deviceId()
    return when {
        currentUserId.isBlank() -> SafetyScanStatus.SESSION_EXPIRED
        request.peerUserId != currentUserId -> SafetyScanStatus.WRONG_ACCOUNT
        request.peerDeviceId != currentDeviceId -> SafetyScanStatus.WRONG_DEVICE
        else -> {
            val ownerFingerprint = request.ownerIdentityFingerprint
            val peerFingerprint = request.peerIdentityFingerprint
            if (!ownerFingerprint.isNullOrBlank() && !peerFingerprint.isNullOrBlank()) {
                val localPeerFingerprint = signal.localIdentityFingerprint()
                val localOwnerFingerprint = signal.remoteIdentityFingerprint(
                    request.ownerUserId,
                    request.ownerDeviceId,
                )
                when {
                    localOwnerFingerprint.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                    localPeerFingerprint.normalizedFingerprintOracle153() != peerFingerprint.normalizedFingerprintOracle153() ->
                        SafetyScanStatus.WRONG_DEVICE_QR
                    localOwnerFingerprint.normalizedFingerprintOracle153() == ownerFingerprint.normalizedFingerprintOracle153() ->
                        SafetyScanStatus.FINGERPRINT_MATCH
                    else -> SafetyScanStatus.FINGERPRINT_MISMATCH
                }
            } else {
                val localCode = signal.safetyCode(request.ownerUserId, request.ownerDeviceId)
                when {
                    localCode.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                    request.safetyCode.isNullOrBlank() -> SafetyScanStatus.INVALID_CODE
                    localCode.normalizedSafetyCodeOracle153() == request.safetyCode.normalizedSafetyCodeOracle153() ->
                        SafetyScanStatus.CODE_MATCH
                    else -> SafetyScanStatus.CODE_MISMATCH
                }
            }
        }
    }
}

// ---------- generators ----------

internal data class QrCase153(
    val ownerUserId: String,
    val ownerDeviceId: Int,
    val peerUserId: String,
    val peerDeviceId: Int,
    val ownerFingerprint: String?,
    val peerFingerprint: String?,
    val safetyCode: String?,
    val currentUserId: String,
    val signalDeviceId: Int,
    val signalLocalFingerprint: String,
    val signalRemoteFingerprint: String?,
    val signalSafetyCode: String?,
)

internal val USER_POOL153 = listOf("u0", "u1", "u2", "", " ", "alice", "U1", "u-1")

internal val DEVICE_POOL153 = listOf(0, 1, 3, 7, -1, Int.MAX_VALUE, Int.MIN_VALUE)

internal fun randomUserId153(rnd: Random): String =
    if (rnd.nextInt(10) < 8) USER_POOL153[rnd.nextInt(USER_POOL153.size)]
    else buildString { repeat(rnd.nextInt(0, 5)) { append(('a' + rnd.nextInt(26))) } }

internal fun randomDeviceId153(rnd: Random): Int =
    if (rnd.nextInt(10) < 7) DEVICE_POOL153[rnd.nextInt(DEVICE_POOL153.size)]
    else rnd.nextInt(-5, 11)

internal fun randomFingerprint153(rnd: Random): String {
    val roll = rnd.nextInt(10)
    if (roll == 0) {
        // Clean hex-like fingerprint, the common real shape.
        val hex = "0123456789abcdef"
        return buildString { repeat(rnd.nextInt(4, 17)) { append(hex[rnd.nextInt(hex.length)]) } }
    }
    if (roll == 1) return listOf("", " ", "  \t ", "---", "...")[rnd.nextInt(5)]
    val alphabet = "abAB019 -_./:\t\u00a0\u00e9\u4e2d\u05d0\u2160\u00aa\u24b6\u02b0\u037a\u00b7\u0307\u0301\uD83D\uDE00"
    val n = rnd.nextInt(0, 13)
    return buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
}

internal fun randomSafetyCode153(rnd: Random): String {
    val roll = rnd.nextInt(10)
    if (roll == 0) {
        return buildString { repeat(rnd.nextInt(4, 9)) { append(('0' + rnd.nextInt(10))) } }
    }
    if (roll == 1) return listOf("", " ", "---", "abc")[rnd.nextInt(4)]
    val alphabet = "0123456789 -./ab\u0661\u0662\u00b2\uFF11\u0307"
    val n = rnd.nextInt(0, 11)
    return buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
}

internal fun randomQrCase153(rnd: Random): QrCase153 {
    val current = if (rnd.nextInt(10) < 8) "u1" else randomUserId153(rnd)
    val peer = if (rnd.nextInt(10) < 7) current else randomUserId153(rnd)
    val signalDev = randomDeviceId153(rnd)
    val peerDev = if (rnd.nextInt(10) < 7) signalDev else randomDeviceId153(rnd)
    val useFingerprints = rnd.nextInt(10) < 6
    val ownerFp = if (useFingerprints) randomFingerprint153(rnd) else if (rnd.nextBoolean()) null else randomFingerprint153(rnd)
    val peerFp = if (useFingerprints) randomFingerprint153(rnd) else if (rnd.nextBoolean()) null else randomFingerprint153(rnd)
    val code = if (useFingerprints) null else randomSafetyCode153(rnd)
    val localFp = if (rnd.nextInt(10) < 6 && ownerFp != null) ownerFp else randomFingerprint153(rnd)
    val remoteFp = if (rnd.nextInt(10) < 6 && ownerFp != null) ownerFp else if (rnd.nextBoolean()) null else randomFingerprint153(rnd)
    val sigCode = if (rnd.nextInt(10) < 6 && code != null) code else if (rnd.nextBoolean()) null else randomSafetyCode153(rnd)
    return QrCase153(
        ownerUserId = randomUserId153(rnd),
        ownerDeviceId = randomDeviceId153(rnd),
        peerUserId = peer,
        peerDeviceId = peerDev,
        ownerFingerprint = ownerFp,
        peerFingerprint = peerFp,
        safetyCode = code,
        currentUserId = current,
        signalDeviceId = signalDev,
        signalLocalFingerprint = localFp,
        signalRemoteFingerprint = remoteFp,
        signalSafetyCode = sigCode,
    )
}

internal fun jsonEscape153(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun qrCaseWireJson153(case: QrCase153): String {
    val sb = StringBuilder()
    sb.append("{\"o\":\"").append(jsonEscape153(case.ownerUserId)).append("\"")
    sb.append(",\"od\":").append(case.ownerDeviceId)
    sb.append(",\"p\":\"").append(jsonEscape153(case.peerUserId)).append("\"")
    sb.append(",\"pd\":").append(case.peerDeviceId)
    if (case.ownerFingerprint != null) sb.append(",\"of\":\"").append(jsonEscape153(case.ownerFingerprint)).append("\"")
    if (case.peerFingerprint != null) sb.append(",\"pf\":\"").append(jsonEscape153(case.peerFingerprint)).append("\"")
    if (case.safetyCode != null) sb.append(",\"sc\":\"").append(jsonEscape153(case.safetyCode)).append("\"")
    sb.append(",\"cur\":\"").append(jsonEscape153(case.currentUserId)).append("\"")
    sb.append(",\"sd\":").append(case.signalDeviceId)
    sb.append(",\"lf\":\"").append(jsonEscape153(case.signalLocalFingerprint)).append("\"")
    if (case.signalRemoteFingerprint != null) sb.append(",\"rf\":\"").append(jsonEscape153(case.signalRemoteFingerprint)).append("\"")
    if (case.signalSafetyCode != null) sb.append(",\"cc\":\"").append(jsonEscape153(case.signalSafetyCode)).append("\"")
    sb.append("}")
    return sb.toString()
}

class QrSafetyScanEvaluatorFuzzTest {

    private class FakeSignal153(
        private val deviceIdValue: Int,
        private val localFingerprintValue: String,
        private val remoteFingerprintValue: String?,
        private val codeValue: String?,
    ) : SafetySignalPort {
        override fun deviceId(): Int = deviceIdValue
        override fun localIdentityFingerprint(): String = localFingerprintValue
        override fun remoteIdentityFingerprint(remoteUserId: String, deviceId: Int): String? = remoteFingerprintValue
        override fun safetyCode(remoteUserId: String, deviceId: Int): String? = codeValue
    }

    private fun check(case: QrCase153) {
        val request = SafetyScanRequest(
            ownerUserId = case.ownerUserId,
            ownerDeviceId = case.ownerDeviceId,
            peerUserId = case.peerUserId,
            peerDeviceId = case.peerDeviceId,
            ownerIdentityFingerprint = case.ownerFingerprint,
            peerIdentityFingerprint = case.peerFingerprint,
            safetyCode = case.safetyCode,
        )
        val signal = FakeSignal153(
            case.signalDeviceId,
            case.signalLocalFingerprint,
            case.signalRemoteFingerprint,
            case.signalSafetyCode,
        )
        assertEquals(
            evaluateOracle153(request, case.currentUserId, signal),
            QrSafetyScanEvaluator.evaluate(request, case.currentUserId, signal),
        )
    }

    private fun baseCase(
        ownerFp: String? = "EF12",
        peerFp: String? = "ab-cd",
        code: String? = null,
        cur: String = "u1",
        peer: String = "u1",
        peerDev: Int = 7,
        sigDev: Int = 7,
        localFp: String = "ab cd",
        remoteFp: String? = "ef12",
        sigCode: String? = "123456",
    ) = QrCase153(
        ownerUserId = "u0", ownerDeviceId = 3,
        peerUserId = peer, peerDeviceId = peerDev,
        ownerFingerprint = ownerFp, peerFingerprint = peerFp, safetyCode = code,
        currentUserId = cur, signalDeviceId = sigDev,
        signalLocalFingerprint = localFp,
        signalRemoteFingerprint = remoteFp, signalSafetyCode = sigCode,
    )

    private fun expect(case: QrCase153, status: SafetyScanStatus) {
        val request = SafetyScanRequest(
            ownerUserId = case.ownerUserId,
            ownerDeviceId = case.ownerDeviceId,
            peerUserId = case.peerUserId,
            peerDeviceId = case.peerDeviceId,
            ownerIdentityFingerprint = case.ownerFingerprint,
            peerIdentityFingerprint = case.peerFingerprint,
            safetyCode = case.safetyCode,
        )
        val signal = FakeSignal153(
            case.signalDeviceId,
            case.signalLocalFingerprint,
            case.signalRemoteFingerprint,
            case.signalSafetyCode,
        )
        assertEquals(status, evaluateOracle153(request, case.currentUserId, signal))
        assertEquals(status, QrSafetyScanEvaluator.evaluate(request, case.currentUserId, signal))
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        expect(baseCase(), SafetyScanStatus.FINGERPRINT_MATCH)
        expect(baseCase(cur = ""), SafetyScanStatus.SESSION_EXPIRED)
        expect(baseCase(cur = " "), SafetyScanStatus.SESSION_EXPIRED)
        expect(baseCase(peer = "u2"), SafetyScanStatus.WRONG_ACCOUNT)
        expect(baseCase(peerDev = 8), SafetyScanStatus.WRONG_DEVICE)
        expect(baseCase(ownerFp = "zz99"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        expect(baseCase(peerFp = "zz"), SafetyScanStatus.WRONG_DEVICE_QR)
        expect(baseCase(ownerFp = null, peerFp = null, code = "123456"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "654321"), SafetyScanStatus.CODE_MISMATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = null), SafetyScanStatus.INVALID_CODE)
        expect(baseCase(ownerFp = null, peerFp = null, code = "123456", sigCode = null), SafetyScanStatus.NO_SESSION)
        expect(baseCase(remoteFp = null), SafetyScanStatus.NO_SESSION)
        expect(baseCase(ownerFp = "", code = "123456"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(peerFp = "", code = "123456"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(localFp = "zz"), SafetyScanStatus.WRONG_DEVICE_QR)
        // Fingerprint normalization pins: final sigma, İ expansion, separators.
        expect(baseCase(ownerFp = "ΟΣ", remoteFp = "ος"), SafetyScanStatus.FINGERPRINT_MATCH)
        expect(baseCase(ownerFp = "ΟΣ", remoteFp = "οσ"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        expect(baseCase(ownerFp = "İ", remoteFp = "İ"), SafetyScanStatus.FINGERPRINT_MATCH)
        expect(baseCase(ownerFp = "İ", remoteFp = "i"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        expect(baseCase(ownerFp = "İ", remoteFp = "i\u0307"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        expect(baseCase(ownerFp = "AΣ", remoteFp = "aς"), SafetyScanStatus.FINGERPRINT_MATCH)
        expect(baseCase(ownerFp = "AΣ", remoteFp = "aσ"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        expect(baseCase(ownerFp = "ab cd", remoteFp = "abcd"), SafetyScanStatus.FINGERPRINT_MATCH)
        expect(baseCase(ownerFp = "AB12", remoteFp = "ab12"), SafetyScanStatus.FINGERPRINT_MATCH)
        // Safety-code normalization pins: separators and letters drop, full-width digits stay.
        expect(baseCase(ownerFp = null, peerFp = null, code = "123-456"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "12a34", sigCode = "1234"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "１２３４５６"), SafetyScanStatus.CODE_MISMATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "123456", sigCode = "123 456"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "", sigCode = "123456"), SafetyScanStatus.INVALID_CODE)
        expect(baseCase(ownerFp = null, peerFp = null, code = "123456", sigCode = ""), SafetyScanStatus.NO_SESSION)
        expect(baseCase(peerDev = Int.MAX_VALUE), SafetyScanStatus.WRONG_DEVICE)
        expect(baseCase(peerDev = Int.MAX_VALUE, sigDev = Int.MAX_VALUE), SafetyScanStatus.FINGERPRINT_MATCH)
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(153003)
        repeat(2000) {
            check(randomQrCase153(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Branch grid: account x device x fingerprint presence x code x signal.
        for (cur in listOf("u1", "", " ")) {
            for (peer in listOf("u1", "u2")) {
                for (peerDev in listOf(7, 8)) {
                    for ((ownerFp, peerFp) in listOf<Pair<String?, String?>>(
                        "EF12" to "ab-cd", null to null, "EF12" to null, null to "ab-cd",
                    )) {
                        for (code in listOf<String?>(null, "123456", "654321")) {
                            for (remoteFp in listOf<String?>( "ef12", null, "zz")) {
                                for (sigCode in listOf<String?>( "123456", null)) {
                                    check(baseCase(ownerFp = ownerFp, peerFp = peerFp, code = code, cur = cur, peer = peer, peerDev = peerDev, remoteFp = remoteFp, sigCode = sigCode))
                                }
                            }
                        }
                    }
                }
            }
        }
        // Fingerprint normalization pairs: raw -> normalized, both directions.
        // Combining marks drop (Mn), CJK/Hebrew stay (Lo), emoji drops (So),
        // roman numerals drop (Nl), ª/modifier letters stay (Lo/Lm).
        val fpPairs = listOf(
            "ab cd" to "abcd",
            "AB-CD" to "abcd",
            "a_b.c/d" to "abcd",
            "EF12" to "ef12",
            "ΟΣ" to "ος",
            "AΣ" to "aς",
            "Σ" to "σ",
            "ﬀ" to "ﬀ",
            "a\u0307b" to "ab",
            "a\u0301b" to "ab",
            "１２３" to "１２３",
            "a\u4e2db" to "a\u4e2db",
            "a\u05d0b" to "a\u05d0b",
            "a\uD83D\uDE00b" to "ab",
            "a\u00a0b" to "ab",
            "a\u200bb" to "ab",
            "a\u2160b" to "ab",
            "a\u00aab" to "a\u00aab",
            "a\u24b6b" to "ab",
            "a\u02b0b" to "a\u02b0b",
            "a\u037ab" to "a\u037ab",
            "a\u00b7b" to "ab",
        )
        for ((raw, norm) in fpPairs) {
            expect(baseCase(ownerFp = raw, remoteFp = norm), SafetyScanStatus.FINGERPRINT_MATCH)
            expect(baseCase(ownerFp = raw, remoteFp = norm + "Z"), SafetyScanStatus.FINGERPRINT_MISMATCH)
        }
        // Empty-normalization fingerprints fall through to NO_SESSION when the
        // signal side is blank; a blank fingerprint never reaches that branch
        // (it takes the safety-code branch instead, INVALID_CODE here).
        expect(baseCase(ownerFp = "---", remoteFp = ""), SafetyScanStatus.NO_SESSION)
        expect(baseCase(ownerFp = "   ", remoteFp = ""), SafetyScanStatus.INVALID_CODE)
        // Safety-code normalization pairs: raw -> digits only.
        val codePairs = listOf(
            "123-456" to "123456",
            "12a34" to "1234",
            "123 456" to "123456",
            "１２３" to "１２３",
            "a1b2" to "12",
            "1.2,3" to "123",
            "١٢٣" to "١٢٣",
            "1\u03072" to "12",
        )
        for ((raw, norm) in codePairs) {
            expect(baseCase(ownerFp = null, peerFp = null, code = raw, sigCode = norm), SafetyScanStatus.CODE_MATCH)
            expect(baseCase(ownerFp = null, peerFp = null, code = raw, sigCode = norm + "7"), SafetyScanStatus.CODE_MISMATCH)
        }
        // Codes that normalize to empty still compare equal when both sides agree.
        expect(baseCase(ownerFp = null, peerFp = null, code = "---", sigCode = "---"), SafetyScanStatus.CODE_MATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "---", sigCode = "123"), SafetyScanStatus.CODE_MISMATCH)
        expect(baseCase(ownerFp = null, peerFp = null, code = "²", sigCode = "²"), SafetyScanStatus.CODE_MATCH)
    }
}
