package com.maodouchat.server.service

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    private val logger = LoggerFactory.getLogger(BotWebhookService::class.java)

    @Volatile
    private var lifecycleState = LifecycleState.STOPPED

    @Volatile
    private var runtime: Runtime? = null

    private val activeLifecycleIds = mutableSetOf<Long>()
    private var lastLifecycleId = 0L
    private val droppedDeliveries = AtomicLong(0)
    private val droppedNotRunning = AtomicLong(0)

    /** Register an application lifecycle and return the token required to stop it. */
    internal fun start(): Long = synchronized(this) {
        val lifecycleId = nextLifecycleId()
        activeLifecycleIds += lifecycleId
        lifecycleState = LifecycleState.RUNNING
        if (runtime?.scope?.coroutineContext?.get(Job)?.isActive != true) {
            runtime?.close()
            runtime = newRuntime()
        }
        lifecycleId
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
        enqueue {
            data class Target(val botId: String, val url: String?, val tokenHash: String)
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
            if (targets.isEmpty()) return@enqueue
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
            // 8.49 修复：按 target 并行投递——此前串行 for 循环里每个慢 webhook 最坏 ~25s
            // （3 次尝试 × 连接+读 6s + 退避），一个多 bot 慢端点群即可长期占住仅有的
            // 4 个 worker，队列满后溢出 fallback、再满即丢事件。并行化后单个慢目标
            // 只拖住自己的协程，不再阻塞其他 target 的投递。
            coroutineScope {
                targets.forEach { t ->
                    launch {
                        val url = t.url
                        // 仅对“无 webhook”的 bot 写入长轮询收件箱；配置了 webhook 的 bot 永不调用
                        // getUpdates 轮询，写入的收件箱行永不被消费，会导致 bot_update_inbox 表无限膨胀。
                        if (url.isNullOrBlank()) {
                            try {
                                com.maodouchat.server.repository.BotRepository.enqueueUpdate(t.botId, body)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                            }
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
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                            }
                        }
                        if (!url.isNullOrBlank()) {
                            // 投递重试（指数退避）+ 失败回退收件箱：事件不允许一次性丢失。
                            // bot 配置了 webhook 但服务端暂时失败时，回退写 inbox 供 bot 轮询兜底。
                            var delivered = false
                            for (attempt in 1..WEBHOOK_MAX_ATTEMPTS) {
                                try {
                                    postJson(url, body, ts, t.tokenHash, t.botId)
                                    delivered = true
                                    break
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    if (attempt < WEBHOOK_MAX_ATTEMPTS) {
                                        delay(WEBHOOK_RETRY_BASE_MS * attempt)
                                    }
                                }
                            }
                            if (!delivered) {
                                try {
                                    com.maodouchat.server.repository.BotRepository.enqueueUpdate(t.botId, body)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    /** Deliver a pre-built update JSON to one bot (inbox + optional webhook). Does not re-enqueue duplicates across chat bots. */
    fun notifyBotDirect(botId: String, bodyJson: String) {
        if (botId.isBlank() || bodyJson.isBlank()) return
        enqueue {
            val target = transaction {
                val now = System.currentTimeMillis()
                val row = BotApps.selectAll()
                    .where { (BotApps.id eq botId) and (BotApps.enabled eq true) }
                    .firstOrNull()
                row?.takeIf { isBotOwnerDeliverable(it[BotApps.ownerUserId], now) }?.let { bot ->
                    val url = bot[BotApps.webhookUrl]?.trim().orEmpty().ifBlank { null }
                    Triple(bot[BotApps.id], url, bot[BotApps.tokenHash])
                }
            } ?: return@enqueue
            val ts = System.currentTimeMillis()
            // Inbox already enqueued by caller; only fire webhook here if configured.
            val url = target.second
            if (!url.isNullOrBlank()) {
                // 与 notifyChatEvent 一致：重试 + 失败回退 inbox，事件不丢失。
                var delivered = false
                for (attempt in 1..WEBHOOK_MAX_ATTEMPTS) {
                    try {
                        postJson(url, bodyJson, ts, target.third, target.first)
                        delivered = true
                        break
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (attempt < WEBHOOK_MAX_ATTEMPTS) {
                            delay(WEBHOOK_RETRY_BASE_MS * attempt)
                        }
                    }
                }
                if (!delivered) {
                    // 8.39：不再补写收件箱——调用方（enqueueCallbackIfAuthorized）已入队，
                    // 此处补投会造成同一条事件重复入 inbox（bot 用 getUpdates 拉取收到两次）。
                    // 只记日志，bot 仍可从既有收件箱行取到该事件。
                    java.util.logging.Logger.getLogger("BotWebhookService")
                        .warning("webhook delivery failed for bot ${target.first}; inbox fallback already enqueued")
                }
            }
        }
    }

    private fun postJson(url: String, body: String, ts: Long, tokenHash: String, botId: String) {
        val signingInput = "$ts.$body"
        val signature = hmacSha256Hex(tokenHash, signingInput)
        com.maodouchat.server.plugins.postPinnedWebhookJson(
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
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

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
    private const val DELIVERY_WORKERS = 4
    /** 8.50 修复 M3：fallback 并发上限（有界执行器，防止慢 webhook 下协程无限堆积）。 */
    private const val FALLBACK_MAX_CONCURRENCY = 64
}
