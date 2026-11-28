package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr196a(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr196a(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt196a(s: String?): String = if (s == null) "NULL" else "S:" + encStr196a(s)

// 错误规约：api:<KIND>:<status|->:<retryAfter|->；io:<SimpleName>；gen:<SimpleName>；anon（匿名 Exception）。
private fun buildError196a(spec: String): Throwable {
    val parts = spec.split(":")
    return when (parts[0]) {
        "api" -> ApiException(
            ApiFailureKind.valueOf(parts[1]),
            parts[2].takeIf { it != "-" }?.toInt(),
            retryAfterSeconds = parts[3].takeIf { it != "-" }?.toLong()
        )
        "io" -> when (parts[1]) {
            "FileNotFoundException" -> FileNotFoundException()
            "SocketTimeoutException" -> SocketTimeoutException()
            "InterruptedIOException" -> InterruptedIOException()
            "EOFException" -> EOFException()
            else -> IOException()
        }
        "gen" -> when (parts[1]) {
            "IllegalArgumentException" -> IllegalArgumentException()
            "IllegalStateException" -> IllegalStateException()
            "NullPointerException" -> NullPointerException()
            "OutOfMemoryError" -> OutOfMemoryError()
            "RuntimeException" -> RuntimeException()
            "Exception" -> Exception()
            else -> RuntimeException()
        }
        else -> object : Exception() {}
    }
}

// transferErrorCode 的独立重实现：ApiException 走 kind_status，其余走类简名（匿名类简名为空 → UNKNOWN）。
private fun errorCodeOracle196a(spec: String): String {
    val parts = spec.split(":")
    return when (parts[0]) {
        "api" -> {
            val status = parts[2].takeIf { it != "-" }?.toInt() ?: 0
            "${parts[1]}_$status"
        }
        "anon" -> "UNKNOWN"
        else -> parts[1]
    }
}

// isRetryableTransferError 的独立重实现：ApiException 看 retryAfter/kind/429/5xx；其余只认 IOException 族。
private fun isRetryableOracle196a(spec: String): Boolean {
    val parts = spec.split(":")
    if (parts[0] != "api") return parts[0] == "io"
    val kind = parts[1]
    val status = parts[2].takeIf { it != "-" }?.toInt()
    val retryAfter = parts[3].takeIf { it != "-" }?.toLong()
    return retryAfter != null || kind == "NETWORK" || kind == "TIMEOUT" ||
        status == 429 || (status ?: 0) >= 500
}

private fun shouldRetryOracle196a(attempts: Int, spec: String, maxRetries: Int): Boolean =
    attempts < maxRetries && isRetryableOracle196a(spec)

private fun claimOracle196a(state: String?): String = when (state) {
    "QUEUED", "UPLOADING", "FAILED" -> AttachmentTransferDisposition.RETRY.name
    else -> AttachmentTransferDisposition.SUCCESS.name
}

private fun promoteOracle196a(state: String?, completed: Boolean): String = when (state) {
    "READY", "SENDING" -> "Finalize"
    "PAUSED" -> "Done"
    else -> if (completed) "Done" else "Retry"
}

private fun requeueOracle196a(state: String?): Boolean =
    state == "UPLOADING" || state == "QUEUED"

private fun errorCodeProd196a(spec: String): String =
    AttachmentTransferErrorPolicy.transferErrorCode(buildError196a(spec))

private fun isRetryableProd196a(spec: String): Boolean =
    AttachmentTransferErrorPolicy.isRetryableTransferError(buildError196a(spec))

private fun shouldRetryProd196a(attempts: Int, spec: String, maxRetries: Int): Boolean =
    AttachmentExecuteDispositionPolicy.shouldRetryUploadFailure(attempts, buildError196a(spec), maxRetries)

private fun claimProd196a(state: String?): String =
    AttachmentTransferErrorPolicy.claimFailureDisposition(state).name

private fun promoteProd196a(state: String?, completed: Boolean): String =
    when (AttachmentExecuteDispositionPolicy.promoteDisposition(state, completed)) {
        PromoteDisposition.Finalize -> "Finalize"
        PromoteDisposition.Done -> "Done"
        PromoteDisposition.Retry -> "Retry"
    }

private fun requeueProd196a(state: String?): Boolean =
    AttachmentExecuteDispositionPolicy.shouldRequeueForRetry(state)

// op: retry | code | claim | promote | requeue。
// retry 用 s1=错误规约、n1=attempts、n2=maxRetries；claim/promote/requeue 用 s1=state、n1=state 为 null、n2=已完成上传。
private data class AttCase196a(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun attOracle196a(c: AttCase196a): String = when (c.op) {
    "retry" -> shouldRetryOracle196a(c.n1, c.s1, c.n2).toString()
    "code" -> errorCodeOracle196a(c.s1)
    "claim" -> claimOracle196a(if (c.n1 == 1) null else c.s1)
    "promote" -> promoteOracle196a(if (c.n1 == 1) null else c.s1, c.n2 == 1)
    "requeue" -> requeueOracle196a(if (c.n1 == 1) null else c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun attProd196a(c: AttCase196a): String = when (c.op) {
    "retry" -> shouldRetryProd196a(c.n1, c.s1, c.n2).toString()
    "code" -> errorCodeProd196a(c.s1)
    "claim" -> claimProd196a(if (c.n1 == 1) null else c.s1)
    "promote" -> promoteProd196a(if (c.n1 == 1) null else c.s1, c.n2 == 1)
    "requeue" -> requeueProd196a(if (c.n1 == 1) null else c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val ATT_STATES196a = listOf(
    "READY", "SENDING", "PAUSED", "QUEUED", "UPLOADING", "FAILED", "DONE", "COMPLETED",
    "", "ready", "Ready", "READY ", " READY", "SENDING\n", "PAUSED\t", "QUEUED ",
    "queued", "uploading", "FAILED ", "FAIL", "UNKNOWN", "null", "None",
    "READY/SENDING", "R", "S", "P", "Q", "U", "F", "READY\u0000", "READY\u00a0"
)

private val ATT_API_KINDS196a = listOf("HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED")

private val ATT_STATUSES196a = listOf("-", "0", "200", "400", "404", "429", "499", "500", "502", "503", "-1", "999", "2147483647", "-2147483648")

private val ATT_RETRIES196a = listOf("-", "0", "1", "30", "60", "-5", "9223372036854775807", "-9223372036854775808")

private val ATT_IO_ERRORS196a = listOf(
    "io:IOException", "io:FileNotFoundException", "io:SocketTimeoutException",
    "io:InterruptedIOException", "io:EOFException"
)

private val ATT_GEN_ERRORS196a = listOf(
    "gen:IllegalArgumentException", "gen:IllegalStateException", "gen:RuntimeException",
    "gen:Exception", "gen:NullPointerException", "gen:OutOfMemoryError"
)

private val ATT_ATTEMPTS196a = listOf(-1, 0, 1, 2, 5, 6, 7, 100, Int.MAX_VALUE, Int.MIN_VALUE)

private val ATT_MAX_RETRIES196a = listOf(-1, 0, 1, 2, 6, 7, 100, Int.MAX_VALUE, Int.MIN_VALUE)

private fun randomApiSpec196a(r: Random): String =
    "api:${ATT_API_KINDS196a[r.nextInt(ATT_API_KINDS196a.size)]}:" +
        ATT_STATUSES196a[r.nextInt(ATT_STATUSES196a.size)] + ":" +
        ATT_RETRIES196a[r.nextInt(ATT_RETRIES196a.size)]

private fun randomErrorSpec196a(r: Random): String = when (r.nextInt(6)) {
    0, 1, 2 -> randomApiSpec196a(r)
    3 -> ATT_IO_ERRORS196a[r.nextInt(ATT_IO_ERRORS196a.size)]
    4 -> ATT_GEN_ERRORS196a[r.nextInt(ATT_GEN_ERRORS196a.size)]
    else -> "anon"
}

private fun randomState196a(r: Random): Pair<String, Int> {
    return if (r.nextInt(8) == 0) {
        "" to 1
    } else {
        ATT_STATES196a[r.nextInt(ATT_STATES196a.size)] to 0
    }
}

private fun randomAttCase196a(r: Random): AttCase196a = when (r.nextInt(5)) {
    0 -> AttCase196a(
        "retry", randomErrorSpec196a(r), "",
        ATT_ATTEMPTS196a[r.nextInt(ATT_ATTEMPTS196a.size)],
        ATT_MAX_RETRIES196a[r.nextInt(ATT_MAX_RETRIES196a.size)]
    )
    1 -> AttCase196a("code", randomErrorSpec196a(r))
    2 -> {
        val (s, isNull) = randomState196a(r)
        AttCase196a("claim", s, "", isNull)
    }
    3 -> {
        val (s, isNull) = randomState196a(r)
        AttCase196a("promote", s, "", isNull, r.nextInt(2))
    }
    else -> {
        val (s, isNull) = randomState196a(r)
        AttCase196a("requeue", s, "", isNull)
    }
}

private fun boundaryAttCase196a(r: Random): AttCase196a = when (r.nextInt(5)) {
    0 -> AttCase196a(
        "retry",
        randomApiSpec196a(r), "",
        listOf(0, 5, 6, -1, Int.MAX_VALUE)[r.nextInt(5)],
        listOf(6, 0, 1, -1, Int.MAX_VALUE)[r.nextInt(5)]
    )
    1 -> AttCase196a("code", randomErrorSpec196a(r))
    2 -> AttCase196a("claim", ATT_STATES196a[r.nextInt(ATT_STATES196a.size)], "", r.nextInt(2))
    3 -> AttCase196a("promote", ATT_STATES196a[r.nextInt(ATT_STATES196a.size)], "", r.nextInt(2), r.nextInt(2))
    else -> AttCase196a("requeue", ATT_STATES196a[r.nextInt(ATT_STATES196a.size)], "", r.nextInt(2))
}

// ---------- tests ----------

class AttachmentExecuteDispositionFuzzTest {

    @Test
    fun attachmentDispositionDifferential196() {
        val r = Random(196101)
        repeat(300) {
            val c = randomAttCase196a(r)
            assertEquals(attOracle196a(c), attProd196a(c))
        }
    }

    @Test
    fun attachmentDispositionBoundaryDifferential196() {
        val r = Random(196102)
        repeat(300) {
            val c = boundaryAttCase196a(r)
            assertEquals(attOracle196a(c), attProd196a(c))
        }
    }

    @Test
    fun attachmentDispositionPinned196() {
        // shouldRetryUploadFailure：次数 < 上限且错误可重试才重试。
        assertEquals(true, shouldRetryProd196a(0, "io:IOException", 6))
        assertEquals(true, shouldRetryProd196a(5, "io:IOException", 6))
        assertEquals(false, shouldRetryProd196a(6, "io:IOException", 6))
        assertEquals(false, shouldRetryProd196a(0, "gen:RuntimeException", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:NETWORK:-:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:TIMEOUT:-:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:HTTP:500:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:HTTP:429:-", 6))
        assertEquals(false, shouldRetryProd196a(0, "api:HTTP:404:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:HTTP:200:30", 6))
        assertEquals(false, shouldRetryProd196a(0, "api:HTTP:200:-", 6))
        assertEquals(false, shouldRetryProd196a(0, "api:INVALID_RESPONSE:499:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "api:UNEXPECTED:999:-", 6))
        assertEquals(false, shouldRetryProd196a(0, "api:HTTP:-1:-", 6))
        assertEquals(true, shouldRetryProd196a(0, "io:FileNotFoundException", 6))
        assertEquals(true, shouldRetryProd196a(0, "io:SocketTimeoutException", 6))
        assertEquals(false, shouldRetryProd196a(0, "gen:OutOfMemoryError", 6))
        // transferErrorCode：ApiException 走 kind_status，其余走类简名，匿名类为空 → UNKNOWN。
        assertEquals("NETWORK_429", errorCodeProd196a("api:NETWORK:429:-"))
        assertEquals("HTTP_0", errorCodeProd196a("api:HTTP:-:-"))
        assertEquals("HTTP_500", errorCodeProd196a("api:HTTP:500:30"))
        assertEquals("IOException", errorCodeProd196a("io:IOException"))
        assertEquals("FileNotFoundException", errorCodeProd196a("io:FileNotFoundException"))
        assertEquals("UNKNOWN", errorCodeProd196a("anon"))
        // claimFailureDisposition：QUEUED/UPLOADING/FAILED 重试，其余成功。
        assertEquals("RETRY", claimProd196a("QUEUED"))
        assertEquals("RETRY", claimProd196a("UPLOADING"))
        assertEquals("RETRY", claimProd196a("FAILED"))
        assertEquals("SUCCESS", claimProd196a("READY"))
        assertEquals("SUCCESS", claimProd196a(null))
        assertEquals("SUCCESS", claimProd196a(""))
        // promoteDisposition：READY/SENDING 继续 finalize；PAUSED 结束；其余已传完结束、未传完重试。
        assertEquals("Finalize", promoteProd196a("READY", false))
        assertEquals("Finalize", promoteProd196a("SENDING", true))
        assertEquals("Done", promoteProd196a("PAUSED", false))
        assertEquals("Done", promoteProd196a(null, true))
        assertEquals("Retry", promoteProd196a(null, false))
        assertEquals("Done", promoteProd196a("DONE", true))
        assertEquals("Retry", promoteProd196a("DONE", false))
        assertEquals("Retry", promoteProd196a("", false))
        // shouldRequeueForRetry：只有 UPLOADING/QUEUED 重排回队。
        assertEquals(true, requeueProd196a("UPLOADING"))
        assertEquals(true, requeueProd196a("QUEUED"))
        assertEquals(false, requeueProd196a("READY"))
        assertEquals(false, requeueProd196a(null))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            AttCase196a("retry", "io:IOException", "", 0, 6),
            AttCase196a("retry", "api:HTTP:500:-", "", 5, 6),
            AttCase196a("code", "api:NETWORK:429:-"),
            AttCase196a("code", "anon"),
            AttCase196a("claim", "QUEUED"),
            AttCase196a("claim", "", "", 1),
            AttCase196a("promote", "PAUSED", "", 0, 0),
            AttCase196a("promote", "", "", 1, 0),
            AttCase196a("requeue", "UPLOADING"),
        )) assertEquals(attOracle196a(c), attProd196a(c))
    }
}
