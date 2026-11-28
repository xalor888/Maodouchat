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
private fun encStr196b(s: String): String {
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

private fun decStr196b(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt196b(s: String?): String = if (s == null) "NULL" else "S:" + encStr196b(s)

// 错误链规约：链节以 >> 相连；"null" 表示空错误。
// api:KIND:status:retry:code:msg（status/retry/code/msg 里的 - 表示 null）；
// io:Name:msg、gen:Name:msg、sig:Name:msg（msg 里的 - 表示 null）；nrd（真类，无消息）。
private fun buildLink196b(link: String, cause: Throwable?): Throwable {
    val tag = link.substringBefore(':')
    return when (tag) {
        "api" -> {
            val p = link.split(":", limit = 6)
            ApiException(
                ApiFailureKind.valueOf(p[1]),
                p[2].takeIf { it != "-" }?.toInt(),
                serverMessage = p[5].takeIf { it != "-" },
                serverCode = p[4].takeIf { it != "-" },
                retryAfterSeconds = p[3].takeIf { it != "-" }?.toLong(),
                cause = cause
            )
        }
        "io" -> {
            val p = link.split(":", limit = 3)
            val msg = p.getOrNull(2)?.takeIf { it != "-" }
            when (p[1]) {
                "FileNotFoundException" -> FileNotFoundException(msg)
                "SocketTimeoutException" -> SocketTimeoutException(msg)
                "InterruptedIOException" -> InterruptedIOException(msg)
                "EOFException" -> EOFException(msg)
                else -> IOException(msg)
            }.also { if (cause != null) it.initCause(cause) }
        }
        "gen" -> {
            val p = link.split(":", limit = 3)
            val msg = p.getOrNull(2)?.takeIf { it != "-" }
            when (p[1]) {
                "IllegalArgumentException" -> IllegalArgumentException(msg, cause)
                "IllegalStateException" -> IllegalStateException(msg, cause)
                "RuntimeException" -> RuntimeException(msg, cause)
                "Exception" -> Exception(msg, cause)
                "NullPointerException" -> NullPointerException(msg).also { if (cause != null) it.initCause(cause) }
                "OutOfMemoryError" -> OutOfMemoryError(msg).also { if (cause != null) it.initCause(cause) }
                else -> RuntimeException(msg, cause)
            }
        }
        "sig" -> {
            val p = link.split(":", limit = 3)
            val msg = p.getOrNull(2)?.takeIf { it != "-" }
            when (p[1]) {
                "NoSessionException" -> NoSessionException(msg, cause)
                "UntrustedIdentityException" -> UntrustedIdentityException(msg, cause)
                "InvalidKeyIdException" -> InvalidKeyIdException(msg, cause)
                "InvalidKeyException" -> InvalidKeyException(msg, cause)
                "StaleKeyException" -> StaleKeyException(msg, cause)
                else -> NoSessionException(msg, cause)
            }
        }
        else -> {
            val e = NoRecipientDevicesException()
            if (cause != null) e.initCause(cause)
            e
        }
    }
}

private fun buildChainError196b(spec: String): Throwable? {
    if (spec == "null") return null
    var cause: Throwable? = null
    for (link in spec.split(">>").reversed()) {
        cause = buildLink196b(link, cause)
    }
    return cause
}

// isTransientCryptoError 的独立重实现：沿 cause 链最多看 6 层，真类 /
// Signal 类简名 / 特征消息子串（大小写敏感）任一命中即瞬时加密错误。
private fun isTransientOracle196b(spec: String): Boolean {
    var t = buildChainError196b(spec)
    var depth = 0
    while (t != null && depth < 6) {
        if (t is NoRecipientDevicesException) return true
        val simpleName = t.javaClass.simpleName
        if (simpleName in setOf(
                "NoSessionException",
                "UntrustedIdentityException",
                "InvalidKeyIdException",
                "InvalidKeyException",
                "StaleKeyException",
                "NoRecipientDevicesException",
            )
        ) return true
        val msg = t.message.orEmpty()
        if (
            "not ready" in msg ||
                "protocol initialization" in msg ||
                "signal_initialization_failed" in msg ||
                "NoRecipientDevices" in simpleName
        ) return true
        t = t.cause
        depth++
    }
    return false
}

private fun notReadyOracle196b(spec: String): Boolean {
    val err = buildChainError196b(spec) as? ApiException ?: return false
    if (err.kind != ApiFailureKind.HTTP || err.statusCode != 409) return false
    val msg = err.serverMessage.orEmpty()
    return "附件尚未" in msg ||
        "尚未上传完成" in msg ||
        (
            msg.contains("attachment", ignoreCase = true) &&
                (
                    msg.contains("not ready", ignoreCase = true) ||
                        msg.contains("upload", ignoreCase = true)
                )
        )
}

private fun acceptedOracle196b(spec: String): Boolean {
    val err = buildChainError196b(spec) as? ApiException ?: return false
    return err.kind == ApiFailureKind.HTTP &&
        err.serverCode?.trim()?.equals("MESSAGE_ALREADY_ACCEPTED", ignoreCase = true) == true
}

private fun retryableOracle196b(spec: String): Boolean {
    val error = buildChainError196b(spec)
    return when (error) {
        is ApiException ->
            notReadyOracle196b(spec) ||
                error.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT) ||
                error.statusCode == 429 ||
                (error.statusCode ?: 0) >= 500
        is IOException -> true
        else -> isTransientOracle196b(spec)
    }
}

private fun isTransientProd196b(spec: String): Boolean =
    AttachmentSendAfterUploadPolicy.isTransientCryptoError(buildChainError196b(spec)!!)

private fun notReadyProd196b(spec: String): Boolean =
    AttachmentSendAfterUploadPolicy.isAttachmentNotReadyConflict(buildChainError196b(spec))

private fun acceptedProd196b(spec: String): Boolean =
    AttachmentSendAfterUploadPolicy.isAlreadyAcceptedDuplicate(buildChainError196b(spec))

private fun retryableProd196b(spec: String): Boolean =
    // isRetryable 只接受非空错误；空错误按不可重试计（与 oracle / 驱动模型一致）。
    buildChainError196b(spec)?.let { AttachmentSendAfterUploadPolicy.isRetryable(it) } ?: false

// op: transient | retryable | notready | accepted；n1=错误为 null。
// transient 的链永不为 null（生成器不产生 null 链）。
private data class SendCase196b(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun sendOracle196b(c: SendCase196b): String = when (c.op) {
    "transient" -> isTransientOracle196b(c.s1).toString()
    "retryable" -> retryableOracle196b(if (c.n1 == 1) "null" else c.s1).toString()
    "notready" -> notReadyOracle196b(if (c.n1 == 1) "null" else c.s1).toString()
    "accepted" -> acceptedOracle196b(if (c.n1 == 1) "null" else c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun sendProd196b(c: SendCase196b): String = when (c.op) {
    "transient" -> isTransientProd196b(c.s1).toString()
    "retryable" -> retryableProd196b(if (c.n1 == 1) "null" else c.s1).toString()
    "notready" -> notReadyProd196b(if (c.n1 == 1) "null" else c.s1).toString()
    "accepted" -> acceptedProd196b(if (c.n1 == 1) "null" else c.s1).toString()
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val SEND_MSGS196b = listOf(
    "-", "", "not ready", "Not Ready", "NOT READY", "attachment not ready",
    "Attachment Not Ready", "ATTACHMENT NOT READY", "protocol initialization failed",
    "protocol initialization", "signal_initialization_failed",
    "NoRecipientDevices", "NoRecipientDevicesException", "no session",
    "NoSessionException", "upload failed", "UPLOAD", "尚未上传完成",
    "附件尚未就绪", "附件尚未", "some error", "x", "not ready?", "not  ready",
    "notready", "ready", "a:b", "a|b", "\nnot ready\n", "  not ready  ",
    "attachment", "upload", "Attachment upload pending", "NOT  READY"
)

private val SEND_API_KINDS196b = listOf("HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED")

private val SEND_STATUSES196b = listOf("-", "0", "200", "400", "404", "409", "429", "499", "500", "502", "503", "-1", "999")

private val SEND_RETRIES196b = listOf("-", "0", "1", "30", "60", "-5")

private val SEND_CODES196b = listOf(
    "-", "", "MESSAGE_ALREADY_ACCEPTED", "message_already_accepted",
    " MESSAGE_ALREADY_ACCEPTED ", "Message_Already_Accepted", "OTHER",
    "message_already_accepted ", " MESSAGE_ALREADY_ACCEPTED"
)

private val SEND_IO196b = listOf(
    "IOException", "FileNotFoundException", "SocketTimeoutException",
    "InterruptedIOException", "EOFException"
)

private val SEND_GEN196b = listOf(
    "IllegalArgumentException", "IllegalStateException", "RuntimeException",
    "Exception", "NullPointerException", "OutOfMemoryError"
)

private val SEND_SIG196b = listOf(
    "NoSessionException", "UntrustedIdentityException", "InvalidKeyIdException",
    "InvalidKeyException", "StaleKeyException", "NoRecipientDevicesException"
)

private fun randomApiLink196b(r: Random): String =
    "api:${SEND_API_KINDS196b[r.nextInt(SEND_API_KINDS196b.size)]}:" +
        SEND_STATUSES196b[r.nextInt(SEND_STATUSES196b.size)] + ":" +
        SEND_RETRIES196b[r.nextInt(SEND_RETRIES196b.size)] + ":" +
        SEND_CODES196b[r.nextInt(SEND_CODES196b.size)] + ":" +
        SEND_MSGS196b[r.nextInt(SEND_MSGS196b.size)]

private fun randomLink196b(r: Random): String = when (r.nextInt(6)) {
    0, 1 -> randomApiLink196b(r)
    2 -> "io:${SEND_IO196b[r.nextInt(SEND_IO196b.size)]}:${SEND_MSGS196b[r.nextInt(SEND_MSGS196b.size)]}"
    3 -> "gen:${SEND_GEN196b[r.nextInt(SEND_GEN196b.size)]}:${SEND_MSGS196b[r.nextInt(SEND_MSGS196b.size)]}"
    4 -> "sig:${SEND_SIG196b[r.nextInt(SEND_SIG196b.size)]}:${SEND_MSGS196b[r.nextInt(SEND_MSGS196b.size)]}"
    else -> "nrd"
}

private fun randomChain196b(r: Random, maxLinks: Int = 4): String =
    (0 until r.nextInt(1, maxLinks + 1)).joinToString(">>") { randomLink196b(r) }

private fun randomSendCase196b(r: Random): SendCase196b = when (r.nextInt(4)) {
    0 -> SendCase196b("transient", randomChain196b(r))
    1 -> {
        val isNull = r.nextInt(8) == 0
        SendCase196b("retryable", if (isNull) "" else randomChain196b(r), "", if (isNull) 1 else 0)
    }
    2 -> {
        val isNull = r.nextInt(8) == 0
        SendCase196b("notready", if (isNull) "" else randomChain196b(r), "", if (isNull) 1 else 0)
    }
    else -> {
        val isNull = r.nextInt(8) == 0
        SendCase196b("accepted", if (isNull) "" else randomChain196b(r), "", if (isNull) 1 else 0)
    }
}

private fun boundaryChain196b(r: Random): String {
    // 目标链节放在不同深度上，探 6 层上限与各判定分支。
    val depth = r.nextInt(8)
    val target = when (r.nextInt(6)) {
        0 -> "nrd"
        1 -> "sig:${SEND_SIG196b[r.nextInt(SEND_SIG196b.size)]}:-"
        2 -> "gen:RuntimeException:not ready"
        3 -> "gen:RuntimeException:protocol initialization failed"
        4 -> "gen:RuntimeException:signal_initialization_failed"
        else -> "api:HTTP:409:-:-:附件尚未上传完成"
    }
    val links = ArrayList<String>()
    repeat(depth) { links.add(randomLink196b(r)) }
    links.add(target)
    if (r.nextInt(2) == 0) links.add(randomLink196b(r))
    return links.joinToString(">>")
}

private fun boundarySendCase196b(r: Random): SendCase196b = when (r.nextInt(4)) {
    0 -> SendCase196b("transient", boundaryChain196b(r))
    1 -> SendCase196b("retryable", boundaryChain196b(r))
    2 -> SendCase196b(
        "notready",
        if (r.nextInt(3) == 0) "api:HTTP:409:-:-:${SEND_MSGS196b[r.nextInt(SEND_MSGS196b.size)]}"
        else boundaryChain196b(r)
    )
    else -> SendCase196b(
        "accepted",
        if (r.nextInt(3) == 0) "api:HTTP:200:-:${SEND_CODES196b[r.nextInt(SEND_CODES196b.size)]}:-"
        else boundaryChain196b(r)
    )
}

// ---------- tests ----------

class AttachmentSendAfterUploadFuzzTest {

    @Test
    fun sendAfterUploadDifferential196() {
        val r = Random(196103)
        repeat(300) {
            val c = randomSendCase196b(r)
            assertEquals(sendOracle196b(c), sendProd196b(c))
        }
    }

    @Test
    fun sendAfterUploadBoundaryDifferential196() {
        val r = Random(196104)
        repeat(300) {
            val c = boundarySendCase196b(r)
            assertEquals(sendOracle196b(c), sendProd196b(c))
        }
    }

    @Test
    fun sendAfterUploadPinned196() {
        // isTransientCryptoError：真类 / Signal 简名 / 特征消息，沿链最多 6 层。
        assertEquals(true, isTransientProd196b("nrd"))
        assertEquals(true, isTransientProd196b("sig:NoSessionException:-"))
        assertEquals(true, isTransientProd196b("sig:UntrustedIdentityException:-"))
        assertEquals(true, isTransientProd196b("sig:InvalidKeyIdException:-"))
        assertEquals(true, isTransientProd196b("sig:InvalidKeyException:-"))
        assertEquals(true, isTransientProd196b("sig:StaleKeyException:-"))
        assertEquals(true, isTransientProd196b("sig:NoRecipientDevicesException:-"))
        assertEquals(true, isTransientProd196b("gen:RuntimeException:not ready"))
        assertEquals(true, isTransientProd196b("gen:RuntimeException:protocol initialization failed"))
        assertEquals(true, isTransientProd196b("gen:RuntimeException:signal_initialization_failed"))
        assertEquals(true, isTransientProd196b("gen:RuntimeException:x>>sig:NoSessionException:-"))
        assertEquals(false, isTransientProd196b("gen:RuntimeException:Not Ready"))
        assertEquals(false, isTransientProd196b("gen:RuntimeException:ready"))
        // 特征消息子串不看异常类型：IOException / ApiException 带 "not ready" 也命中。
        assertEquals(true, isTransientProd196b("io:IOException:not ready"))
        assertEquals(true, isTransientProd196b("api:HTTP:500:-:-:not ready"))
        assertEquals(false, isTransientProd196b("gen:RuntimeException:some error"))
        // 链深 6 层：目标在第 7 层（depth 6）看不见。
        assertEquals(
            true,
            isTransientProd196b("gen:RuntimeException:a>>gen:RuntimeException:b>>gen:RuntimeException:c>>gen:RuntimeException:d>>gen:RuntimeException:e>>sig:NoSessionException:-")
        )
        assertEquals(
            false,
            isTransientProd196b("gen:RuntimeException:a>>gen:RuntimeException:b>>gen:RuntimeException:c>>gen:RuntimeException:d>>gen:RuntimeException:e>>gen:RuntimeException:f>>sig:NoSessionException:-")
        )
        // isAttachmentNotReadyConflict：只认 HTTP 409 + 未就绪文案。
        assertEquals(true, notReadyProd196b("api:HTTP:409:-:-:附件尚未上传完成"))
        assertEquals(true, notReadyProd196b("api:HTTP:409:-:-:附件尚未就绪"))
        assertEquals(true, notReadyProd196b("api:HTTP:409:-:-:attachment not ready"))
        assertEquals(true, notReadyProd196b("api:HTTP:409:-:-:Attachment upload pending"))
        // 英文分支忽略大小写，全大写也命中。
        assertEquals(true, notReadyProd196b("api:HTTP:409:-:-:ATTACHMENT NOT READY"))
        assertEquals(false, notReadyProd196b("api:HTTP:404:-:-:附件尚未上传完成"))
        assertEquals(false, notReadyProd196b("api:NETWORK:409:-:-:附件尚未上传完成"))
        assertEquals(false, notReadyProd196b("api:HTTP:409:-:-:some error"))
        assertEquals(false, notReadyProd196b("gen:RuntimeException:附件尚未上传完成"))
        assertEquals(false, notReadyProd196b("null"))
        // isAlreadyAcceptedDuplicate：只认 HTTP + serverCode 忽略大小写比对。
        assertEquals(true, acceptedProd196b("api:HTTP:200:-:MESSAGE_ALREADY_ACCEPTED:-"))
        assertEquals(true, acceptedProd196b("api:HTTP:200:-:message_already_accepted:-"))
        assertEquals(true, acceptedProd196b("api:HTTP:200:-: MESSAGE_ALREADY_ACCEPTED :-"))
        assertEquals(false, acceptedProd196b("api:HTTP:200:-:OTHER:-"))
        assertEquals(false, acceptedProd196b("api:HTTP:200:-:-:-"))
        assertEquals(false, acceptedProd196b("api:NETWORK:200:-:MESSAGE_ALREADY_ACCEPTED:-"))
        assertEquals(false, acceptedProd196b("null"))
        // isRetryable：409 未就绪冲突 / NETWORK/TIMEOUT / 429 / 5xx / IOException / 瞬时加密错误。
        assertEquals(true, retryableProd196b("api:HTTP:409:-:-:附件尚未上传完成"))
        assertEquals(true, retryableProd196b("api:NETWORK:-:-:-:-"))
        assertEquals(true, retryableProd196b("api:TIMEOUT:-:-:-:-"))
        assertEquals(true, retryableProd196b("api:HTTP:429:-:-:-"))
        assertEquals(true, retryableProd196b("api:HTTP:500:-:-:-"))
        assertEquals(false, retryableProd196b("api:HTTP:404:-:-:-"))
        assertEquals(true, retryableProd196b("io:IOException:-"))
        assertEquals(true, retryableProd196b("gen:RuntimeException:not ready"))
        assertEquals(false, retryableProd196b("gen:RuntimeException:some error"))
        assertEquals(false, retryableProd196b("null"))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            SendCase196b("transient", "nrd"),
            SendCase196b("transient", "gen:RuntimeException:x>>sig:NoSessionException:-"),
            SendCase196b("retryable", "api:HTTP:409:-:-:附件尚未上传完成"),
            SendCase196b("retryable", "", "", 1),
            SendCase196b("notready", "api:HTTP:409:-:-:attachment not ready"),
            SendCase196b("notready", "", "", 1),
            SendCase196b("accepted", "api:HTTP:200:-:MESSAGE_ALREADY_ACCEPTED:-"),
            SendCase196b("accepted", "", "", 1),
        )) assertEquals(sendOracle196b(c), sendProd196b(c))
    }
}
