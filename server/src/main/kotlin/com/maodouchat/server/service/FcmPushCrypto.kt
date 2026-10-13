package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 密码学簇：唤醒载荷 HMAC 签名与 per-user 推导密钥。Mac 非线程安全，ThreadLocal 复用。

private val hmacSha256Mac = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

fun signPayload(recipientId: String, data: Map<String, String>): Map<String, String> {
    val ts = System.currentTimeMillis().toString()
    val base = data.filterKeys { it != "sig" && it != "ts" }
    val canonical = base.keys.sorted().joinToString("&") { "${it}=${base[it]}" }
    val payload = "$canonical&ts=$ts"
    val sig = try {
        val mac = hmacSha256Mac.get().apply { reset() }
        mac.init(SecretKeySpec(pushKeyForUser(recipientId).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
    return data + ("ts" to ts) + ("sig" to sig)
}

fun pushKeyForUser(recipientId: String): String = try {
    val mac = hmacSha256Mac.get().apply { reset() }
    mac.init(SecretKeySpec(ServerConfig.pushHmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    mac.doFinal(recipientId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
} catch (_: Exception) {
    ""
}
