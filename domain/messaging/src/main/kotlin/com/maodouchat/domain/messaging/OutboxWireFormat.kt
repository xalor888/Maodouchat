package com.maodouchat.domain.messaging

/**
 * V2 线上约定：`ciphertext_type` 必须匹配 `^[A-Z0-9_-]{1,32}$`（服务端 `MessagingV2Routing`
 * 的校验），而直发密码学常量是 `prekey` / `signal`（小写）。此前把小写直接写上线，服务端一律回
 * 400 INVALID_MESSAGE——两侧各自的测试都发现不了（服务端测试全用 "TEXT"，客户端测试只到信封为止）。
 * G23 的真 HTTP 端到端第一次把它暴露出来。
 *
 * 归一化只放在**线上边界**，不动密码学常量：解密侧的 `else` 分支本来就会按内容判断
 * PreKey/Signal，所以大写值不影响解密。
 */
object OutboxWireFormat {
    fun ciphertextType(value: String): String = value.uppercase()
}
