package com.maodouchat.server.service

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotWebhookOutbox
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fire-and-forget webhook delivery for bots that joined a chat.
 *
 * Payload is metadata-first (no E2EE plaintext for user-to-user Signal content).
 * Requests are signed with HMAC-SHA256 over "{ts}.{body}" using tokenHash as secret material
 * so developers can verify authenticity without storing the raw bot token server-side beyond hash.
 */
object BotWebhookService {
    private enum class LifecycleState { RUNNING, STOPPED }

    private data class Runtime(
        val scope: CoroutineScope,
        val queue: Channel<suspend () -> Unit>
    )

    private data class OutboxEntry(
        val id: String,
        val botId: String,
        val url: String,
        val tokenHash: String,
        val body: String,
        val ts: Long,
    )

    private val logger = LoggerFactory.getLogger(BotWebhookService::class.java)

    @Volatile
    private var lifecycleState = LifecycleState.STOPPED

    @Volatile
    private var runtime: Runtime? = null

    private val activeLifecycleIds = mutableSetOf<Long>()
    private var lastLifecycleId = 0L
    private val droppedDeliveries = AtomicLong(0)
    private val droppedNotRunning = AtomicLong(0)
    /** 本进程 worker 标识：outbox 租约归属，避免多实例互相覆盖。 */
    private val workerId = UUID.randomUUID().toString()

    private enum class DeliveryOutcome { DELIVERED, DEAD, SKIPPED }

    /** Register an application lifecycle and return the token required to stop it. */
    internal fun start(): Long = synchronized(this) {
        val lifecycleId = nextLifecycleId()
        activeLifecycleIds += lifecycleId
        lifecycleState = LifecycleState.RUNNING
        if (runtime?.scope?.coroutineContext?.get(Job)?.isActive != true) {
            runtime?.close()
            runtime = newRuntime()
        }
        replayStalePending()
        lifecycleId
    }

    /**
     * B12：重放——认领所有“无活跃租约”的 PENDING outbox 重新投递。租约过期或未认领的
     * 行都会在 [deliverWebhook] 内以原子 UPDATE 抢占，多实例下同一行只会被一个 worker 拿走。
     */
    internal fun replayStalePending() {
        val now = System.currentTimeMillis()
        val stale = transaction {
            BotWebhookOutbox.selectAll()
                .where {
                    (BotWebhookOutbox.status eq "PENDING") and
                        ((BotWebhookOutbox.leaseUntil lessEq now) or BotWebhookOutbox.leaseOwner.isNull())
                }
                .map { row ->
                    OutboxEntry(
                        id = row[BotWebhookOutbox.id],
                        botId = row[BotWebhookOutbox.botId],
                        url = row[BotWebhookOutbox.url],
                        tokenHash = row[BotWebhookOutbox.tokenHash],
                        body = row[BotWebhookOutbox.body],
                        ts = row[BotWebhookOutbox.ts],
                    )
                }
        }
        stale.forEach { entry ->
            enqueue {
                deliverWebhook(entry.id, entry.url, entry.body, entry.ts, entry.tokenHash, entry.botId)
            }
        }
    }

    /** Unregister one application lifecycle; the final shutdown cancels in-flight delivery. */
    internal fun shutdown(lifecycleId: Long) {
        val runtimeToClose = synchronized(this) {
            if (!activeLifecycleIds.remove(lifecycleId) || activeLifecycleIds.isNotEmpty()) {
                return@synchronized null
            }
            lifecycleState = LifecycleState.STOPPED
            runtime.also { runtime = null }
        }
        runtimeToClose?.close()
    }

    private fun newRuntime(): Runtime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = Channel<suspend () -> Unit>(capacity = DELIVERY_QUEUE_CAPACITY)
        repeat(DELIVERY_WORKERS) {
            scope.launch {
                for (delivery in queue) {
                    try {
                        delivery()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        logger.warn("Bot webhook delivery failed", error)
                    }
                }
            }
        }
        // B12：周期性重放——回收崩溃 worker 遗留的过期租约，兜底进程中途退出。
        scope.launch {
            while (isActive) {
                delay(WEBHOOK_REPLAY_INTERVAL_MS)
                try {
                    replayStalePending()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    logger.warn("Bot webhook replay sweep failed", e)
                }
            }
        }
        return Runtime(scope, queue)
    }

    private fun Runtime.close() {
        queue.close()
        scope.cancel()
    }

    private fun nextLifecycleId(): Long {
        do {
            lastLifecycleId = if (lastLifecycleId == Long.MAX_VALUE) 1L else lastLifecycleId + 1L
        } while (lastLifecycleId in activeLifecycleIds)
        return lastLifecycleId
    }

    private fun activeQueue(): Channel<suspend () -> Unit>? {
        if (lifecycleState != LifecycleState.RUNNING) return null
        val current = runtime
        if (current?.scope?.coroutineContext?.get(Job)?.isActive == true) return current.queue
        return synchronized(this) {
            if (lifecycleState != LifecycleState.RUNNING) return@synchronized null
            runtime?.takeIf { it.scope.coroutineContext[Job]?.isActive == true }
                ?.queue
                ?: newRuntime().also { runtime = it }.queue
        }
    }

    // 8.48 修复 L5/M4：兜底执行器——服务未运行或队列满时不再静默丢弃事件，
    // 改用独立 fallback scope 即时执行一次（投递失败仍走既有的「指数退避 + 回退收件箱」）。
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 8.50 修复 M3：有界 fallback 并发——原无界 launch 在慢 webhook + 500 人群下协程无限堆积
    //（每条事件挂起最多 6s 退避重试）；满则丢弃并计数告警（与 FcmPushService drop 策略一致）
    private val fallbackSemaphore = Semaphore(FALLBACK_MAX_CONCURRENCY)
    private val droppedFallbackOverflow = AtomicLong(0)

    /** 8.50 修复 M3：在受控并发下执行一次 fallback 投递，满则丢弃。 */
    private fun runFallback(delivery: suspend () -> Unit) {
        if (!fallbackSemaphore.tryAcquire()) {
            val dropped = droppedFallbackOverflow.incrementAndGet()
            if (dropped == 1L || dropped % 100L == 0L) {
                logger.warn("Bot webhook fallback overflow; dropped {} events", dropped)
            }
            return
        }
        fallbackScope.launch {
            try {
                delivery()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                logger.warn("Bot webhook fallback delivery failed", e)
            } finally {
                fallbackSemaphore.release()
            }
        }
    }

    /** Align webhook fanout with BotRepository.authenticate: suspended/deleted/restricted owners must not keep receiving bot events. */
    private fun isBotOwnerDeliverable(ownerUserId: String, now: Long): Boolean {
        val owner = Users.selectAll().where { Users.id eq ownerUserId }.firstOrNull() ?: return false
        return owner[Users.deletedAt] == null &&
            owner[Users.suspendedUntil] <= now &&
            owner[Users.messageRestrictedUntil] <= now &&
            owner[Users.postRestrictedUntil] <= now
    }

    private fun enqueue(delivery: suspend () -> Unit) {
        val queue = activeQueue() ?: run {
            // 服务未 start / 已 shutdown 时静默丢弃会掩盖 bot 能力“看起来没反应”的问题，
            // 此前直接丢弃（webhook bot 连收件箱兜底都没有）→ 事件永久丢失。
            val dropped = droppedNotRunning.incrementAndGet()
            if (dropped == 1L || dropped % 100L == 0L) {
                logger.warn("Bot webhook service not running; falling back direct delivery ({} total)", dropped)
            }
            runFallback(delivery)
            return
        }
        if (queue.trySend(delivery).isSuccess) return
        // 队列满（1024）：slow webhook 场景极易触发，丢弃会让事件永久丢失 → fallback 执行一次
        val dropped = droppedDeliveries.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            logger.warn("Bot webhook queue full; falling back direct delivery ({} total)", dropped)
        }
        runFallback(delivery)
    }

    fun notifyChatEvent(
        chatId: String,
        event: String,
        messageId: String? = null,
        senderId: String? = null,
        type: String? = null,
        textPreview: String? = null,
        sealedSender: Boolean = false
    ) {
        if (chatId.isBlank()) return
        data class Target(val botId: String, val url: String?, val tokenHash: String)
        // B12：同步解析目标 + 落 outbox/inbox，再异步投递——进程在 enqueue 与投递之间退出也不丢事件。
        val targets = transaction {
            val now = System.currentTimeMillis()
            val botIds = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .map { it[ChatParticipants.userId] }
                .filter { it.startsWith("bot_") }
                // 8.33 修复：事件发送者（bot 自己）不得收到自身事件的 webhook 回声，否则
                // bot 发消息 → 收到 bot_message → 自动回复 → 无限回声循环
                .filter { it != senderId }
            if (botIds.isEmpty()) return@transaction emptyList()
            val rows = BotApps.selectAll()
                .where { (BotApps.id inList botIds) and (BotApps.enabled eq true) }
                .toList()
            val activeOwnerIds = rows.map { it[BotApps.ownerUserId] }.distinct()
                .filter { isBotOwnerDeliverable(it, now) }
                .toSet()
            if (activeOwnerIds.isEmpty()) return@transaction emptyList()
            rows.filter { it[BotApps.ownerUserId] in activeOwnerIds }
                .map { row ->
                    val url = row[BotApps.webhookUrl]?.trim().orEmpty().ifBlank { null }
                    Target(row[BotApps.id], url, row[BotApps.tokenHash])
                }
        }
        if (targets.isEmpty()) return
        val ts = System.currentTimeMillis()
        val body = buildJsonObject {
            put("event", event)
            put("chatId", chatId)
            if (messageId != null) put("messageId", messageId)
            if (senderId != null) put(
                "senderId",
                SealedSenderDelivery.webhookSenderId(senderId, sealedSender) ?: senderId
            )
            if (sealedSender) put("sealedSender", true)
            if (type != null) put("type", type)
            if (!textPreview.isNullOrBlank()) put("text", textPreview.take(500))
            val slash = textPreview?.trim().orEmpty()
            if (slash.startsWith("/")) {
                val cmd = slash.removePrefix("/")
                    .substringBefore(" ")
                    .substringBefore("@")
                    .lowercase()
                    .take(64)
                if (cmd.isNotBlank()) put("command", cmd)
            }
            put("ts", ts)
        }.toString()
        // 同步路由：无 webhook 的 bot 直接入长轮询收件箱；有 webhook 的先落 outbox，再异步投递。
        val pending = ArrayList<OutboxEntry>()
        targets.forEach { t ->
            if (t.url.isNullOrBlank()) {
                try {
                    com.maodouchat.server.repository.BotRepository.enqueueUpdate(t.botId, body)
                } catch (e: Exception) {
                    logger.warn("Bot inbox enqueue failed for bot {}", t.botId, e)
                }
            } else {
                val id = "wh_${UUID.randomUUID()}"
                insertOutbox(id, t.botId, t.url, t.tokenHash, body, ts)
                pending += OutboxEntry(id, t.botId, t.url, t.tokenHash, body, ts)
            }
            // Audit slash commands for developer console.
            val cmdName = runCatching {
                val slash = textPreview?.trim().orEmpty()
                if (!slash.startsWith("/")) null
                else slash.removePrefix("/").substringBefore(" ").substringBefore("@").lowercase().take(64)
            }.getOrNull()
            if (!cmdName.isNullOrBlank()) {
                try {
                    com.maodouchat.server.repository.BotRepository.logCommand(
                        t.botId, chatId, senderId, "/$cmdName"
                    )
                } catch (e: Exception) {
                    logger.warn("Bot command audit failed for bot {}", t.botId, e)
                }
            }
        }
        // 异步投递（worker 池并行）；本 worker 投成 DEAD 时回退收件箱兜底。
        pending.forEach { entry ->
            enqueue {
                val outcome = deliverWebhook(entry.id, entry.url, entry.body, entry.ts, entry.tokenHash, entry.botId)
                if (outcome == DeliveryOutcome.DEAD) {
                    try {
                        com.maodouchat.server.repository.BotRepository.enqueueUpdate(entry.botId, entry.body)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        logger.warn("Bot inbox fallback failed for bot {}", entry.botId, e)
                    }
                }
            }
        }
    }


    /** Deliver a pre-built update JSON to one bot (inbox + optional webhook). Does not re-enqueue duplicates across chat bots. */
    fun notifyBotDirect(botId: String, bodyJson: String) {
        if (botId.isBlank() || bodyJson.isBlank()) return
        val target = transaction {
            val now = System.currentTimeMillis()
            val row = BotApps.selectAll()
                .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                .firstOrNull()
            row?.takeIf { isBotOwnerDeliverable(it[BotApps.ownerUserId], now) }?.let { bot ->
                val url = bot[BotApps.webhookUrl]?.trim().orEmpty().ifBlank { null }
                Triple(bot[BotApps.id], url, bot[BotApps.tokenHash])
            }
        } ?: return
        val ts = System.currentTimeMillis()
        // Inbox already enqueued by caller; only fire webhook here if configured.
        val url = target.second
        if (url.isNullOrBlank()) return
        val id = "wh_${UUID.randomUUID()}"
        insertOutbox(id, target.first, url, target.third, bodyJson, ts)
        enqueue {
            val outcome = deliverWebhook(id, url, bodyJson, ts, target.third, target.first)
            if (outcome == DeliveryOutcome.DEAD) {
                // 8.39：不再补写收件箱——调用方（enqueueCallbackIfAuthorized）已入队，
                // 此处补投会造成同一条事件重复入 inbox（bot 用 getUpdates 拉取收到两次）。
                logger.warn(
                    "webhook delivery failed for bot {}; inbox fallback already enqueued",
                    target.first
                )
            }
        }
    }

    private suspend fun deliverWebhook(
        outboxId: String,
        url: String,
        body: String,
        ts: Long,
        tokenHash: String,
        botId: String
    ): DeliveryOutcome {
        // 抢占租约：PENDING 且（无租约 或 租约已过期）才可认领，原子更新防多实例重投。
        if (!claimLease(outboxId)) return DeliveryOutcome.SKIPPED
        var attempts = 0
        for (attempt in 1..WEBHOOK_MAX_ATTEMPTS) {
            attempts = attempt
            try {
                postJson(url, body, ts, tokenHash, botId)
                finalizeOutbox(outboxId, "DELIVERED", attempts)
                return DeliveryOutcome.DELIVERED
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                val retry = isRetryableWebhookFailure(e) && attempt < WEBHOOK_MAX_ATTEMPTS
                if (retry) {
                    logger.warn(
                        "Bot webhook attempt {}/{} failed for bot {}; retrying",
                        attempt,
                        WEBHOOK_MAX_ATTEMPTS,
                        botId,
                        e
                    )
                    delay(WEBHOOK_RETRY_BASE_MS * attempt)
                } else {
                    logger.warn(
                        "Bot webhook delivery failed for bot {} after {} attempt(s)",
                        botId,
                        attempt,
                        e
                    )
                    finalizeOutbox(outboxId, "DEAD", attempts)
                    return DeliveryOutcome.DEAD
                }
            }
        }
        finalizeOutbox(outboxId, "DEAD", attempts)
        return DeliveryOutcome.DEAD
    }

    /** 同步落一条 PENDING outbox（先持久化再异步投递，进程退出不丢事件）。 */
    internal fun insertOutbox(
        id: String, botId: String, url: String, tokenHash: String, body: String, ts: Long
    ) {
        val now = System.currentTimeMillis()
        transaction {
            BotWebhookOutbox.insert {
                it[BotWebhookOutbox.id] = id
                it[BotWebhookOutbox.botId] = botId
                it[BotWebhookOutbox.url] = url
                it[BotWebhookOutbox.tokenHash] = tokenHash
                it[BotWebhookOutbox.body] = body
                it[BotWebhookOutbox.ts] = ts
                it[BotWebhookOutbox.attempts] = 0
                it[BotWebhookOutbox.status] = "PENDING"
                it[BotWebhookOutbox.leaseOwner] = null
                it[BotWebhookOutbox.leaseUntil] = 0L
                it[BotWebhookOutbox.createdAt] = now
                it[BotWebhookOutbox.updatedAt] = now
            }
        }
    }

    /** 原子抢占租约；false 表示已终态或已被其他活跃 worker 认领。 */
    internal fun claimLease(id: String, now: Long = System.currentTimeMillis()): Boolean =
        transaction {
            BotWebhookOutbox.update({
                (BotWebhookOutbox.id eq id) and
                    (BotWebhookOutbox.status eq "PENDING") and
                    ((BotWebhookOutbox.leaseUntil lessEq now) or BotWebhookOutbox.leaseOwner.isNull())
            }) {
                it[BotWebhookOutbox.leaseOwner] = workerId
                it[BotWebhookOutbox.leaseUntil] = now + WEBHOOK_LEASE_MS
                it[BotWebhookOutbox.updatedAt] = now
            }
        } > 0

    /** 终态落库：仅当仍由本 worker 持有时才写，避免覆盖已被抢占的行。 */
    internal fun finalizeOutbox(id: String, status: String, attempts: Int) {
        val now = System.currentTimeMillis()
        transaction {
            BotWebhookOutbox.update({
                (BotWebhookOutbox.id eq id) and (BotWebhookOutbox.leaseOwner eq workerId)
            }) {
                it[BotWebhookOutbox.status] = status
                it[BotWebhookOutbox.attempts] = attempts
                it[BotWebhookOutbox.leaseOwner] = null
                it[BotWebhookOutbox.leaseUntil] = 0L
                it[BotWebhookOutbox.updatedAt] = now
            }
        }
    }

    private fun postJson(url: String, body: String, ts: Long, tokenHash: String, botId: String) {
        val signingInput = "$ts.$body"
        val signature = hmacSha256Hex(tokenHash, signingInput)
        val response = com.maodouchat.server.plugins.postPinnedWebhookJson(
            url = url,
            body = body,
            headers = mapOf(
                "User-Agent" to "Maodouchat-BotWebhook/1.0",
                "X-Maodouchat-Bot-Id" to botId,
                "X-Maodouchat-Timestamp" to ts.toString(),
                "X-Maodouchat-Signature" to "sha256=$signature"
            ),
            connectTimeoutMs = 3_000,
            readTimeoutMs = 3_000
        )
        if (response.statusCode !in 200..299) {
            throw WebhookHttpException(response.statusCode, botId)
        }
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        // Byte 是有符号的：`"%02x".format(it)` 会对 0x80..0xFF 符号扩展成 "ffffff80"。
        return raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isRetryableWebhookFailure(error: Exception): Boolean {
        if (error is SecurityException || error is IllegalArgumentException) return false
        if (error is WebhookHttpException) {
            val code = error.statusCode
            return code == 408 || code == 429 || code in 500..599
        }
        return true
    }

    private class WebhookHttpException(
        val statusCode: Int,
        botId: String
    ) : Exception("webhook HTTP $statusCode for bot $botId")

    /** Helper for docs / self-test: hash of raw bot token (same as BotRepository). */
    fun hashTokenLike(token: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(token.trim().toByteArray(StandardCharsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    private const val DELIVERY_QUEUE_CAPACITY = 1_024
    /** webhook 投递总尝试次数（1 次立即 + 2 次退避重试）。 */
    private const val WEBHOOK_MAX_ATTEMPTS = 3
    /** 重试基础退避毫秒（attempt=1 时 2s，attempt=2 时 4s）。 */
    private const val WEBHOOK_RETRY_BASE_MS = 2_000L
    /** B12：worker 租约时长（覆盖 3 次尝试 + 连接/读超时 + 退避），过期可被重放抢占。 */
    private const val WEBHOOK_LEASE_MS = 60_000L
    /** B12：周期性重放间隔，回收崩溃 worker 遗留的过期租约。 */
    private const val WEBHOOK_REPLAY_INTERVAL_MS = 30_000L
    private const val DELIVERY_WORKERS = 4
    /** 8.50 修复 M3：fallback 并发上限（有界执行器，防止慢 webhook 下协程无限堆积）。 */
    private const val FALLBACK_MAX_CONCURRENCY = 64
}
