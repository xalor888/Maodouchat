package com.maodouchat.server.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Fire-and-forget webhook delivery for bots that joined a chat.
 *
 * Payload is metadata-first (no E2EE plaintext for user-to-user Signal content).
 * Requests are signed with HMAC-SHA256 over "{ts}.{body}" using tokenHash as secret material
 * so developers can verify authenticity without storing the raw bot token server-side beyond hash.
 */
// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
// Mac 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
private val hmacSha256ThreadLocal = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

object BotWebhookService {
    internal val logger = LoggerFactory.getLogger(BotWebhookService::class.java)

    @Volatile
    internal var lifecycleState = LifecycleState.STOPPED

    @Volatile
    internal var runtime: Runtime? = null

    internal val activeLifecycleIds = mutableSetOf<Long>()
    internal var lastLifecycleId = 0L
    internal val droppedDeliveries = AtomicLong(0)
    internal val droppedNotRunning = AtomicLong(0)
    /** 本进程 worker 标识：outbox 租约归属，避免多实例互相覆盖。 */
    internal val workerId = UUID.randomUUID().toString()

    // 8.48 修复 L5/M4：兜底执行器——服务未运行或队列满时不再静默丢弃事件，
    // 改用独立 fallback scope 即时执行一次（投递失败仍走既有的「指数退避 + 回退收件箱」）。
    internal val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 8.50 修复 M3：有界 fallback 并发——原无界 launch 在慢 webhook + 500 人群下协程无限堆积
    //（每条事件挂起最多 6s 退避重试）；满则丢弃并计数告警（与 FcmPushService drop 策略一致）
    internal val fallbackSemaphore = Semaphore(FALLBACK_MAX_CONCURRENCY)
    internal val droppedFallbackOverflow = AtomicLong(0)

    internal fun hmacSha256Hex(secret: String, message: String): String {
        val mac = hmacSha256ThreadLocal.get().apply { reset() }
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        // Byte 是有符号的：`"%02x".format(it)` 会对 0x80..0xFF 符号扩展成 "ffffff80"。
        return raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Helper for docs / self-test: hash of raw bot token (same as BotRepository). */
    fun hashTokenLike(token: String): String {
        val md = sha256ThreadLocal.get().apply { reset() }
        val dig = md.digest(token.trim().toByteArray(StandardCharsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    internal const val DELIVERY_QUEUE_CAPACITY = 1_024
    /** webhook 投递总尝试次数（1 次立即 + 2 次退避重试）。 */
    internal const val WEBHOOK_MAX_ATTEMPTS = 3
    /** 重试基础退避毫秒（attempt=1 时 2s，attempt=2 时 4s）。 */
    internal const val WEBHOOK_RETRY_BASE_MS = 2_000L
    /** B12：worker 租约时长（覆盖 3 次尝试 + 连接/读超时 + 退避），过期可被重放抢占。 */
    internal const val WEBHOOK_LEASE_MS = 60_000L
    /** B12：周期性重放间隔，回收崩溃 worker 遗留的过期租约。 */
    internal const val WEBHOOK_REPLAY_INTERVAL_MS = 30_000L
    internal const val DELIVERY_WORKERS = 4
    /** 8.50 修复 M3：fallback 并发上限（有界执行器，防止慢 webhook 下协程无限堆积）。 */
    internal const val FALLBACK_MAX_CONCURRENCY = 64
}
