// Fuzz / random-oracle tests for AttachmentFinalizePolicy
// decideFinalizeFailure (checklist line 931, 151st block). Test-only,
// zero production changes. The oracle is hand-written from the
// production source: an unverified 400/404/410 ApiException means the
// upload went stale (re-upload); otherwise the retryable verdict of
// AttachmentSendAfterUploadPolicy decides, including its
// crypto-error name/message walk. The sibling helpers
// isAttachmentNotReadyConflict / isAlreadyAcceptedDuplicate are
// already fuzz-covered by block 114; only decideFinalizeFailure and
// the crypto walk it reaches are new here.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

internal data class ErrB151(
    val kind: String, // api | io | socket | runtime | crypto | norecip
    val apiKind: String? = null,
    val status: Int? = null,
    val msg: String? = null,
    val cryptoName: String? = null,
    val cause: ErrB151? = null,
)

// ---------- oracle ----------

// Error descriptor: the shape the oracle reasons about. The CI test
// builds a real Throwable from it (buildErrB151, below the class);
// the differential probe does the same from the wire JSON.

private val CRYPTO_NAMES151 = setOf(
    "NoSessionException",
    "UntrustedIdentityException",
    "InvalidKeyIdException",
    "InvalidKeyException",
    "StaleKeyException",
    "NoRecipientDevicesException",
)

internal fun simpleNameOracle151(e: ErrB151): String = when (e.kind) {
    "api" -> "ApiException"
    "io" -> "IOException"
    "socket" -> "SocketTimeoutException"
    "runtime" -> "RuntimeException"
    "crypto" -> e.cryptoName!!
    "norecip" -> "NoRecipientDevicesException"
    else -> "RuntimeException"
}

// Kotlin contains(other, ignoreCase=true) compares char by char with
// Character upper/lower case folding. The needles here are ASCII and
// the message alphabet is ASCII + CJK, so per-char folding is exact.
internal fun containsIgnoreCaseOracle151(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    outer@ for (i in 0..s.length - needle.length) {
        for (j in needle.indices) {
            val a = s[i + j]
            val b = needle[j]
            if (a == b) continue
            if (a.uppercaseChar() == b.uppercaseChar()) continue
            if (a.lowercaseChar() == b.lowercaseChar()) continue
            continue@outer
        }
        return true
    }
    return false
}

internal fun isTransientCryptoErrorOracle151(e: ErrB151?): Boolean {
    var cur = e
    var depth = 0
    while (cur != null && depth < 6) {
        if (cur.kind == "norecip") return true
        val simpleName = simpleNameOracle151(cur)
        if (simpleName in CRYPTO_NAMES151) return true
        val msg = cur.msg.orEmpty()
        if ("not ready" in msg || "protocol initialization" in msg ||
            "signal_initialization_failed" in msg || "NoRecipientDevices" in simpleName
        ) return true
        cur = cur.cause
        depth++
    }
    return false
}

internal fun isAttachmentNotReadyConflictOracle151(e: ErrB151): Boolean {
    if (e.kind != "api" || e.apiKind != "HTTP" || e.status != 409) return false
    val msg = e.msg.orEmpty()
    return "附件尚未" in msg || "尚未上传完成" in msg ||
        (containsIgnoreCaseOracle151(msg, "attachment") &&
            (containsIgnoreCaseOracle151(msg, "not ready") || containsIgnoreCaseOracle151(msg, "upload")))
}

internal fun isRetryableOracle151(e: ErrB151): Boolean = when (e.kind) {
    "api" -> isAttachmentNotReadyConflictOracle151(e) ||
        e.apiKind in setOf("NETWORK", "TIMEOUT") ||
        e.status == 429 ||
        (e.status ?: 0) >= 500
    "io", "socket" -> true
    else -> isTransientCryptoErrorOracle151(e)
}

internal fun decideFinalizeFailureOracle151(verified: Boolean, e: ErrB151): AttachmentFinalizeFailure {
    val staleUpload = !verified && e.kind == "api" && e.status in setOf(400, 404, 410)
    return when {
        staleUpload -> AttachmentFinalizeFailure.ReuploadRequired
        isRetryableOracle151(e) -> AttachmentFinalizeFailure.Transient
        else -> AttachmentFinalizeFailure.Failed
    }
}

// ---------- generators ----------

private val API_KIND_POOL_B151 = arrayOf("HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED")

private val STATUS_POOL_B151 = arrayOf<Int?>(null, 200, 400, 404, 408, 409, 410, 429, 500, 502, 599, 600, -1)

private val CRYPTO_NAME_POOL151 = arrayOf(
    "NoSessionException", "UntrustedIdentityException", "InvalidKeyIdException",
    "InvalidKeyException", "StaleKeyException", "NoRecipientDevicesException",
    "SessionException", "NoSessionError", "InvalidKey", "CryptoException",
    "NoRecipientDevices", "StaleKey", "KeyException",
)

private val MSG_POOL_B151 = arrayOf<String?>(
    null, "", "x", "timeout", "boom",
    "附件尚未上传", "尚未上传完成，请重试", "附件尚未就绪",
    "attachment not ready", "Attachment upload pending", "ATTACHMENT NOT READY",
    "not ready", "protocol initialization failed", "signal_initialization_failed",
    "NoRecipientDevices", "upload stalled", "ATTACHMENT", "Upload",
)

private val TEXT_RANGES_B151 = arrayOf(
    0x21 to 0x7E, 0xA0 to 0x2FF, 0x300 to 0x2FFF, 0x4E00 to 0x9FFF,
)

internal fun randomTextB151(rnd: Random, maxLen: Int = 16): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) {
        val (lo, hi) = TEXT_RANGES_B151[rnd.nextInt(TEXT_RANGES_B151.size)]
        val cp = rnd.nextInt(lo, hi + 1)
        sb.append(Character.toChars(cp))
    }
    return sb.toString()
}

internal fun randomMsgB151(rnd: Random): String? =
    if (rnd.nextInt(10) < 6) MSG_POOL_B151[rnd.nextInt(MSG_POOL_B151.size)] else randomTextB151(rnd)

internal fun randomErrB151(rnd: Random, depth: Int = 0): ErrB151 {
    val kind = when (rnd.nextInt(100)) {
        in 0 until 40 -> "api"
        in 40 until 65 -> "runtime"
        in 65 until 85 -> "crypto"
        in 85 until 93 -> "io"
        in 93 until 97 -> "socket"
        else -> "norecip"
    }
    val cause = if (depth < 2 && rnd.nextInt(100) < 35) randomErrB151(rnd, depth + 1) else null
    return when (kind) {
        "api" -> ErrB151(kind, API_KIND_POOL_B151[rnd.nextInt(API_KIND_POOL_B151.size)], STATUS_POOL_B151[rnd.nextInt(STATUS_POOL_B151.size)], msg = randomMsgB151(rnd), cause = cause)
        "crypto" -> ErrB151(kind, msg = randomMsgB151(rnd), cryptoName = CRYPTO_NAME_POOL151[rnd.nextInt(CRYPTO_NAME_POOL151.size)], cause = cause)
        else -> ErrB151(kind, msg = randomMsgB151(rnd), cause = cause)
    }
}

internal fun errBJson151(e: ErrB151): JSONObject {
    val o = JSONObject()
    o.put("k", e.kind)
    e.apiKind?.let { o.put("ak", it) }
    e.status?.let { o.put("st", it) }
    e.msg?.let { o.put("m", it) }
    e.cryptoName?.let { o.put("cn", it) }
    e.cause?.let { o.put("c", errBJson151(it)) }
    return o
}

internal fun wireFinDecide151(verified: Boolean, e: ErrB151): String {
    val root = JSONObject()
    root.put("v", verified)
    root.put("e", errBJson151(e))
    return root.toString()
}

internal fun wireErrB151(e: ErrB151): String = errBJson151(e).toString()

class AttachmentFinalizeFailureFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val api400 = ErrB151("api", "HTTP", 400)
        val api404 = ErrB151("api", "HTTP", 404)
        val api410 = ErrB151("api", "HTTP", 410)
        val api200 = ErrB151("api", "HTTP", 200)
        // Unverified stale codes go back for re-upload, verified ones do not.
        for (err in listOf(api400, api404, api410)) {
            assertEquals(AttachmentFinalizeFailure.ReuploadRequired, decideFinalizeFailureOracle151(false, err))
            assertEquals(decideFinalizeFailureOracle151(false, err), decideFinalizeFailure(false, buildErrB151(err)))
            assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(true, err))
            assertEquals(decideFinalizeFailureOracle151(true, err), decideFinalizeFailure(true, buildErrB151(err)))
        }
        // Non-stale ApiException 200 is a plain failure.
        assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(false, api200))
        assertEquals(decideFinalizeFailureOracle151(false, api200), decideFinalizeFailure(false, buildErrB151(api200)))
        // Network / timeout kinds are retryable whatever the status.
        for (kind in listOf("NETWORK", "TIMEOUT")) {
            val err = ErrB151("api", kind, 200)
            assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, err))
            assertEquals(decideFinalizeFailureOracle151(false, err), decideFinalizeFailure(false, buildErrB151(err)))
        }
        // 429 and 5xx are retryable; 4xx other than stale codes are not.
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, ErrB151("api", "HTTP", 429)))
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, ErrB151("api", "HTTP", 500)))
        assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(false, ErrB151("api", "HTTP", 403)))
        // IOException is always retryable.
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, ErrB151("io")))
        assertEquals(decideFinalizeFailureOracle151(false, ErrB151("io")), decideFinalizeFailure(false, buildErrB151(ErrB151("io"))))
        // 409 with the not-ready message is a conflict retry, not stale.
        val conflict = ErrB151("api", "HTTP", 409, msg = "attachment not ready")
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, conflict))
        assertEquals(decideFinalizeFailureOracle151(false, conflict), decideFinalizeFailure(false, buildErrB151(conflict)))
        // Same 409 without the message is a plain failure.
        assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(false, ErrB151("api", "HTTP", 409, msg = "nope")))
        // Crypto-name walk: a named Signal error anywhere in the chain.
        val named = ErrB151("crypto", cryptoName = "NoSessionException")
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, named))
        assertEquals(decideFinalizeFailureOracle151(false, named), decideFinalizeFailure(false, buildErrB151(named)))
        val wrapped = ErrB151("runtime", cause = ErrB151("crypto", cryptoName = "StaleKeyException"))
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, wrapped))
        assertEquals(decideFinalizeFailureOracle151(false, wrapped), decideFinalizeFailure(false, buildErrB151(wrapped)))
        // A decoy name that merely looks similar does not count.
        val decoy = ErrB151("crypto", cryptoName = "SessionException")
        assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(false, decoy))
        assertEquals(decideFinalizeFailureOracle151(false, decoy), decideFinalizeFailure(false, buildErrB151(decoy)))
        // "NoRecipientDevices" as a substring of the class name counts.
        val bare = ErrB151("crypto", cryptoName = "NoRecipientDevices")
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, bare))
        assertEquals(decideFinalizeFailureOracle151(false, bare), decideFinalizeFailure(false, buildErrB151(bare)))
        // Message triggers count even on a plain RuntimeException.
        val msgHit = ErrB151("runtime", msg = "signal_initialization_failed")
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, msgHit))
        // The real NoRecipientDevicesException type counts on its own.
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, ErrB151("norecip")))
        assertEquals(decideFinalizeFailureOracle151(false, ErrB151("norecip")), decideFinalizeFailure(false, buildErrB151(ErrB151("norecip"))))
        // Depth: a transient node 6 deep is out of reach.
        fun chain(depth: Int): ErrB151 =
            if (depth == 0) ErrB151("crypto", cryptoName = "NoSessionException") else ErrB151("runtime", cause = chain(depth - 1))
        assertEquals(AttachmentFinalizeFailure.Transient, decideFinalizeFailureOracle151(false, chain(5)))
        assertEquals(AttachmentFinalizeFailure.Failed, decideFinalizeFailureOracle151(false, chain(6)))
        assertEquals(decideFinalizeFailureOracle151(false, chain(6)), decideFinalizeFailure(false, buildErrB151(chain(6))))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(151002)
        repeat(1500) {
            val verified = rnd.nextBoolean()
            val err = randomErrB151(rnd)
            assertEquals(
                decideFinalizeFailureOracle151(verified, err),
                decideFinalizeFailure(verified, buildErrB151(err)),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // apiKind x status grid, verified and unverified.
        for (kind in API_KIND_POOL_B151) {
            for (status in STATUS_POOL_B151) {
                for (verified in listOf(false, true)) {
                    val err = ErrB151("api", kind, status)
                    assertEquals(
                        decideFinalizeFailureOracle151(verified, err),
                        decideFinalizeFailure(verified, buildErrB151(err)),
                    )
                }
            }
        }
        // Crypto name grid: every name, top-level and one wrapper deep.
        for (name in CRYPTO_NAME_POOL151) {
            val err = ErrB151("crypto", cryptoName = name)
            assertEquals(decideFinalizeFailureOracle151(false, err), decideFinalizeFailure(false, buildErrB151(err)))
            val wrapped = ErrB151("runtime", cause = err)
            assertEquals(decideFinalizeFailureOracle151(false, wrapped), decideFinalizeFailure(false, buildErrB151(wrapped)))
        }
        // Message trigger grid on a plain RuntimeException.
        val messages = listOf<String?>(
            null, "", "not ready", "NOT READY", "protocol initialization",
            "PROTOCOL INITIALIZATION", "signal_initialization_failed",
            "attachment", "attachment upload", "ATTACHMENT NOT READY",
            "附件尚未", "尚未上传完成", "附件尚未上传完成",
        )
        for (msg in messages) {
            val err = ErrB151("runtime", msg = msg)
            assertEquals(decideFinalizeFailureOracle151(false, err), decideFinalizeFailure(false, buildErrB151(err)))
            // Same message on a 409 conflict ApiException.
            val conflict = ErrB151("api", "HTTP", 409, msg = msg)
            assertEquals(decideFinalizeFailureOracle151(false, conflict), decideFinalizeFailure(false, buildErrB151(conflict)))
        }
        // Chain-depth sweep: named crypto error at depth d is seen iff d < 6.
        for (d in 0..7) {
            var err = ErrB151("crypto", cryptoName = "NoSessionException")
            repeat(d) { err = ErrB151("runtime", cause = err) }
            assertEquals(decideFinalizeFailureOracle151(false, err), decideFinalizeFailure(false, buildErrB151(err)))
        }
    }
}

// Test-only Throwable builder: turns a descriptor into the real
// exception the production code classifies. Never called by the oracle.
internal fun buildErrB151(e: ErrB151): Throwable {
    val cause = e.cause?.let(::buildErrB151)
    return when (e.kind) {
        "api" -> ApiException(ApiFailureKind.valueOf(e.apiKind!!), e.status, e.msg, cause = cause)
        "io" -> if (cause == null) java.io.IOException(e.msg) else java.io.IOException(e.msg, cause)
        "socket" -> java.net.SocketTimeoutException(e.msg).apply { if (cause != null) initCause(cause) }
        "crypto" -> when (e.cryptoName) {
            // Stand-ins below carry exactly these simple names; production
            // only reads javaClass.simpleName, never the type. The
            // NoRecipientDevicesException name is the real class in this
            // package, so it is built directly (its `is` check fires first
            // in production anyway).
            "NoSessionException" -> NoSessionException(e.msg, cause)
            "UntrustedIdentityException" -> UntrustedIdentityException(e.msg, cause)
            "InvalidKeyIdException" -> InvalidKeyIdException(e.msg, cause)
            "InvalidKeyException" -> InvalidKeyException(e.msg, cause)
            "StaleKeyException" -> StaleKeyException(e.msg, cause)
            "NoRecipientDevicesException" -> NoRecipientDevicesException()
            "SessionException" -> SessionException(e.msg, cause)
            "NoSessionError" -> NoSessionError(e.msg, cause)
            "InvalidKey" -> InvalidKey(e.msg, cause)
            "CryptoException" -> CryptoException(e.msg, cause)
            "NoRecipientDevices" -> NoRecipientDevices(e.msg, cause)
            "StaleKey" -> StaleKey(e.msg, cause)
            else -> KeyException(e.msg, cause)
        }
        "norecip" -> NoRecipientDevicesException()
        else -> if (cause == null) RuntimeException(e.msg) else RuntimeException(e.msg, cause)
    }
}

// Test-only stand-ins with the exact simple names production matches
// on (it only reads javaClass.simpleName, never the type). Private to
// this file, so they cannot collide with the real Signal classes.
private class NoSessionException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class UntrustedIdentityException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class InvalidKeyIdException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class InvalidKeyException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class StaleKeyException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class SessionException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class NoSessionError(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class InvalidKey(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class CryptoException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class NoRecipientDevices(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class StaleKey(message: String?, cause: Throwable?) : RuntimeException(message, cause)
private class KeyException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
