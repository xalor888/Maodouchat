package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AttachmentUploadStatusResponse
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// validate reimplemented as a first-failure reporter: the six
// requires run in production order and the first failure wins;
// a passing status reports OK. The id regex is restated by hand
// and the hash comparison lowercases the expected value only.
internal val ATTACHMENT_ID_REGEX167 = Regex("^att_[A-Za-z0-9_-]{20,100}$")

internal fun validateOutcomeOracle167(
    status: AttachmentUploadStatusResponse,
    expectedSha256: String,
    expectedSize: Long
): String {
    if (!status.id.matches(ATTACHMENT_ID_REGEX167)) return "attachment_id_invalid"
    if (status.cipherSha256 != expectedSha256.lowercase()) return "attachment_hash_mismatch"
    if (status.cipherSize != expectedSize) return "attachment_size_mismatch"
    if (status.uploadedBytes !in 0L..expectedSize) return "attachment_offset_invalid"
    if (status.status !in setOf("UPLOADING", "UPLOADED", "COMMITTED")) return "attachment_status_invalid"
    if (status.complete && status.uploadedBytes != expectedSize) return "attachment_completion_invalid"
    return "OK"
}

// Production validate throws; map the first require message to the
// same outcome string the oracle returns.
internal fun validateOutcomeProduction167(
    status: AttachmentUploadStatusResponse,
    expectedSha256: String,
    expectedSize: Long
): String = try {
    AttachmentUploadStatusPolicy.validate(status, expectedSha256, expectedSize)
    "OK"
} catch (e: IllegalArgumentException) {
    e.message ?: "?"
}

// ---------- generators ----------

internal fun attStatus167(
    id: String = "att_" + "a".repeat(20),
    cipherSha256: String = "abc123",
    cipherSize: Long = 100L,
    uploadedBytes: Long = 50L,
    status: String = "UPLOADING",
    complete: Boolean = false
): AttachmentUploadStatusResponse = AttachmentUploadStatusResponse(
    id = id,
    cipherSha256 = cipherSha256,
    cipherSize = cipherSize,
    uploadedBytes = uploadedBytes,
    status = status,
    expiresAt = 0L,
    complete = complete
)

internal data class AttCase167(
    val status: AttachmentUploadStatusResponse,
    val expectedSha256: String,
    val expectedSize: Long
)

internal val ATT_ID_POOL167: List<String> = listOf(
    "att_" + "a".repeat(20),
    "att_" + "a".repeat(19),
    "att_" + "a".repeat(21),
    "att_" + "a".repeat(100),
    "att_" + "a".repeat(101),
    "att_" + "A0-_".repeat(5),
    "att_" + "a".repeat(19) + "!",
    "att_" + "a".repeat(20) + "\n",
    "att_" + "a".repeat(19) + "\n",
    "att_",
    "",
    "att",
    "ATT_" + "a".repeat(20),
    "att_" + "a".repeat(20) + " ",
    "att_\u00e9".repeat(10),
)

internal val ATT_SHA_POOL167: List<String> = listOf(
    "", "abc123", "ABC123", "AbC123", "aBc123", "abcdef", "ABCDEF",
    "0123456789abcdef", "0123456789ABCDEF", "xyz", "XYZ", "a b", "abc\n",
    "\u00e9\u00c9", "\u0130", "\u03a3", "\u03c3\u03a3",
)

internal val ATT_SIZE_POOL167: List<Long> = listOf(
    0L, 1L, -1L, 2L, 99L, 100L, 101L, Long.MAX_VALUE, Long.MIN_VALUE,
)

internal val ATT_STATUS_POOL167: List<String> = listOf(
    "UPLOADING", "UPLOADED", "COMMITTED", "DONE", "uploading",
    "Uploaded", "", "UPLOADED ", "COMMITTED\n", "QUEUED",
)

internal fun randomAttCase167(rnd: Random): AttCase167 {
    val expectedSize = ATT_SIZE_POOL167[rnd.nextInt(ATT_SIZE_POOL167.size)]
    val expectedSha = ATT_SHA_POOL167[rnd.nextInt(ATT_SHA_POOL167.size)]
    val sha = if (rnd.nextInt(10) < 6) {
        // Often agree with the expected value up to case, so the
        // later requires get exercised too.
        when (rnd.nextInt(3)) {
            0 -> expectedSha
            1 -> expectedSha.uppercase()
            else -> expectedSha.lowercase()
        }
    } else {
        ATT_SHA_POOL167[rnd.nextInt(ATT_SHA_POOL167.size)]
    }
    val up = when (rnd.nextInt(10)) {
        0 -> expectedSize
        1 -> expectedSize - 1
        2 -> expectedSize + 1
        else -> ATT_SIZE_POOL167[rnd.nextInt(ATT_SIZE_POOL167.size)]
    }
    val size = if (rnd.nextInt(10) < 7) expectedSize
    else ATT_SIZE_POOL167[rnd.nextInt(ATT_SIZE_POOL167.size)]
    return AttCase167(
        attStatus167(
            id = ATT_ID_POOL167[rnd.nextInt(ATT_ID_POOL167.size)],
            cipherSha256 = sha,
            cipherSize = size,
            uploadedBytes = up,
            status = ATT_STATUS_POOL167[rnd.nextInt(ATT_STATUS_POOL167.size)],
            complete = rnd.nextBoolean()
        ),
        expectedSha,
        expectedSize
    )
}

internal fun attWireJson167(c: AttCase167): String {
    fun esc(s: String): String {
        val sb = StringBuilder()
        sb.append("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        val v = ch.code
                        sb.append("0123456789abcdef"[v shr 12])
                        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                        sb.append("0123456789abcdef"[v and 0xF])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
    val s = c.status
    val sb = StringBuilder()
    sb.append("{\"id\":${esc(s.id)},\"sha\":${esc(s.cipherSha256)},")
    sb.append("\"size\":${s.cipherSize},\"up\":${s.uploadedBytes},")
    sb.append("\"status\":${esc(s.status)},\"complete\":${s.complete},")
    sb.append("\"expSha\":${esc(c.expectedSha256)},\"expSize\":${c.expectedSize}}")
    return sb.toString()
}

class AttachmentUploadStatusPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        fun check(
            status: AttachmentUploadStatusResponse,
            expectedSha256: String,
            expectedSize: Long,
            expected: String
        ) {
            assertEquals(
                expected,
                validateOutcomeProduction167(status, expectedSha256, expectedSize),
                "validate(id=${status.id},sha=${status.cipherSha256},size=${status.cipherSize}," +
                    "up=${status.uploadedBytes},status=${status.status},complete=${status.complete}," +
                    "expSha=$expectedSha256,expSize=$expectedSize)"
            )
            assertEquals(
                expected,
                validateOutcomeOracle167(status, expectedSha256, expectedSize),
                "oracle(id=${status.id},sha=${status.cipherSha256},size=${status.cipherSize}," +
                    "up=${status.uploadedBytes},status=${status.status},complete=${status.complete}," +
                    "expSha=$expectedSha256,expSize=$expectedSize)"
            )
        }
        // Fully valid base case.
        check(attStatus167(), "abc123", 100L, "OK")
        check(attStatus167(uploadedBytes = 100L, complete = true), "abc123", 100L, "OK")
        check(attStatus167(cipherSize = 0L, uploadedBytes = 0L, complete = true), "abc123", 0L, "OK")
        // Id length boundaries: 20 ok, 19 and 101 fail.
        check(attStatus167(id = "att_" + "a".repeat(19)), "abc123", 100L, "attachment_id_invalid")
        check(attStatus167(id = "att_" + "a".repeat(100)), "abc123", 100L, "OK")
        check(attStatus167(id = "att_" + "a".repeat(101)), "abc123", 100L, "attachment_id_invalid")
        check(attStatus167(id = "att_" + "a".repeat(19) + "!"), "abc123", 100L, "attachment_id_invalid")
        check(attStatus167(id = "ATT_" + "a".repeat(20)), "abc123", 100L, "attachment_id_invalid")
        // Trailing newline after a full-length id still fails the full match.
        check(attStatus167(id = "att_" + "a".repeat(20) + "\n"), "abc123", 100L, "attachment_id_invalid")
        // Hash: only the expected value is lowercased.
        check(attStatus167(cipherSha256 = "ABC123"), "abc123", 100L, "attachment_hash_mismatch")
        check(attStatus167(cipherSha256 = "abc123"), "ABC123", 100L, "OK")
        check(attStatus167(cipherSha256 = "AbC123"), "aBc123", 100L, "attachment_hash_mismatch")
        // Same uppercase on both sides still mismatches: only the
        // expected value is lowercased, the status sha is taken as-is.
        check(attStatus167(cipherSha256 = "ABC123"), "ABC123", 100L, "attachment_hash_mismatch")
        // Size mismatch.
        check(attStatus167(cipherSize = 99L), "abc123", 100L, "attachment_size_mismatch")
        check(attStatus167(cipherSize = 101L), "abc123", 100L, "attachment_size_mismatch")
        // Offset out of range.
        check(attStatus167(uploadedBytes = -1L), "abc123", 100L, "attachment_offset_invalid")
        check(attStatus167(uploadedBytes = 101L), "abc123", 100L, "attachment_offset_invalid")
        check(attStatus167(uploadedBytes = 100L), "abc123", 100L, "OK")
        // Unknown status.
        check(attStatus167(status = "DONE"), "abc123", 100L, "attachment_status_invalid")
        check(attStatus167(status = "uploading"), "abc123", 100L, "attachment_status_invalid")
        check(attStatus167(status = "UPLOADED"), "abc123", 100L, "OK")
        check(attStatus167(status = "COMMITTED"), "abc123", 100L, "OK")
        // Complete without full upload.
        check(attStatus167(uploadedBytes = 50L, complete = true), "abc123", 100L, "attachment_completion_invalid")
        // First failure wins: bad id beats bad hash.
        check(attStatus167(id = "x", cipherSha256 = "ZZZ"), "abc123", 100L, "attachment_id_invalid")
        // Negative expected size: the 0..size range is empty.
        check(attStatus167(cipherSize = -5L, uploadedBytes = 0L), "abc123", -5L, "attachment_offset_invalid")
        // Long extremes agree.
        check(
            attStatus167(cipherSize = Long.MAX_VALUE, uploadedBytes = Long.MAX_VALUE, complete = true),
            "abc123", Long.MAX_VALUE, "OK"
        )
        check(
            attStatus167(cipherSize = Long.MIN_VALUE, uploadedBytes = 0L),
            "abc123", Long.MIN_VALUE, "attachment_offset_invalid"
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(167003)
        repeat(4000) {
            val c = randomAttCase167(rnd)
            assertEquals(
                validateOutcomeOracle167(c.status, c.expectedSha256, c.expectedSize),
                validateOutcomeProduction167(c.status, c.expectedSha256, c.expectedSize),
                "validate(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Id length boundaries with an otherwise valid status.
        for (n in listOf(0, 1, 18, 19, 20, 21, 99, 100, 101)) {
            val s = attStatus167(id = "att_" + "a".repeat(n))
            assertEquals(
                validateOutcomeOracle167(s, "abc123", 100L),
                validateOutcomeProduction167(s, "abc123", 100L),
                "sweep-id-len(n=$n)"
            )
        }
        // Bad class member at the boundary length.
        for (bad in listOf("!", " ", "\n", "\u00e9", "-", "_", "0", "Z")) {
            val s = attStatus167(id = "att_" + "a".repeat(19) + bad)
            assertEquals(
                validateOutcomeOracle167(s, "abc123", 100L),
                validateOutcomeProduction167(s, "abc123", 100L),
                "sweep-id-char(bad=$bad)"
            )
        }
        // Hash case pairs: status sha x expected sha.
        val shas = listOf("abc", "ABC", "AbC", "aBc", "", "ABC123", "abc123", "AbC123")
        for (statusSha in shas) {
            for (expectedSha in shas) {
                val s = attStatus167(cipherSha256 = statusSha)
                assertEquals(
                    validateOutcomeOracle167(s, expectedSha, 100L),
                    validateOutcomeProduction167(s, expectedSha, 100L),
                    "sweep-sha(status=$statusSha,expected=$expectedSha)"
                )
            }
        }
        // Size / offset / status / completion cross product.
        for (expectedSize in listOf(0L, 1L, 100L)) {
            for (uploaded in listOf(0L, 1L, 99L, 100L, 101L, -1L)) {
                for (cipherSize in listOf(0L, 1L, 99L, 100L, 101L)) {
                    for (status in listOf("UPLOADING", "UPLOADED", "COMMITTED", "DONE", "uploading", "")) {
                        for (complete in listOf(false, true)) {
                            val s = attStatus167(
                                cipherSize = cipherSize, uploadedBytes = uploaded,
                                status = status, complete = complete
                            )
                            assertEquals(
                                validateOutcomeOracle167(s, "abc123", expectedSize),
                                validateOutcomeProduction167(s, "abc123", expectedSize),
                                "sweep-size(exp=$expectedSize,up=$uploaded,cipher=$cipherSize," +
                                    "status=$status,complete=$complete)"
                            )
                        }
                    }
                }
            }
        }
    }
}
