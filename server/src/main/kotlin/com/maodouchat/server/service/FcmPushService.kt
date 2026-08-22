package com.maodouchat.server.service

import com.google.auth.oauth2.GoogleCredentials
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.NotificationSettingsResponse
import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRecord
import com.maodouchat.server.repository.PushTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Optional FCM HTTP v1 sender.
 *
 * Push payloads intentionally contain routing metadata only. Message content stays
 * encrypted end to end and is never copied into an FCM request.
 */
class FcmPushService(
    private val pushTokenRepository: PushTokenRepository,
    private val preferenceRepository: NotificationPreferenceRepository,
    private val projectId: String = ServerConfig.fcmProjectId,
    private val serviceAccountFile: String = ServerConfig.fcmServiceAccountFile
) {
    private val logger = LoggerFactory.getLogger(FcmPushService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deliveries = Channel<Delivery>(capacity = DELIVERY_QUEUE_CAPACITY)
    private val callDeliveries = Channel<Delivery>(capacity = CALL_QUEUE_CAPACITY)
    private val droppedDeliveries = AtomicLong(0)
    private val droppedCallDeliveries = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val clientLock = Any()
    private val clientDelegate = lazy {
        // 8.31 运维修复：显式超时（CIO 默认值偏长且不可控）；FCM 是兜底通道，
        // 不能让慢响应占住 worker。
        HttpClient(CIO) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
    }

    init {
        // 8.48 修复 H8：批量预取 settings/tokens 后逐个投递（此前逐收件人各 2 次 DB 查询，
        // 群消息 200 人离线 → 400 次查询）。worker 每次尽量取满一批共享一次批量查询。
        repeat(DELIVERY_WORKERS) { launchWorker(deliveries, isCall = false) }
        repeat(CALL_DELIVERY_WORKERS) { launchWorker(callDeliveries, isCall = true) }
    }

    /** 8.50 修复 L4：worker 循环显式 catch——deliverBatch 内部 runCatching 只重抛
     * CancellationException，任何新增的非取消异常不得静默杀死 worker（否则队列满后丢推送）。 */
    private fun launchWorker(queue: Channel<Delivery>, isCall: Boolean) {
        scope.launch {
            while (true) {
                val first = try {
                    queue.receive()
                } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    break
                }
                val batch = mutableListOf(first)
                while (batch.size < DELIVERY_BATCH) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                try {
                    deliverBatch(batch, isCall = isCall)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("FCM worker batch failed for {} deliveries", batch.size, e)
                }
            }
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        deliveries.close()
        callDeliveries.close()
        scope.cancel()
        synchronized(clientLock) {
            if (clientDelegate.isInitialized()) runCatching { clientDelegate.value.close() }
        }
    }
    private val credentials by lazy {
        FileInputStream(serviceAccountFile).use {
            GoogleCredentials.fromStream(it).createScoped(FCM_SCOPE)
        }
    }

    val isConfigured: Boolean
        get() = projectId.isNotBlank() && serviceAccountFile.isNotBlank() && File(serviceAccountFile).isFile

    fun enqueueEncryptedMessage(
        recipientIds: Collection<String>,
        chatId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        sealedSender: Boolean = false
    ) {
        if (closed.get() || !isConfigured) return
        val recipients = recipientIds.asSequence().filter { it != senderId }.distinct().toList()
        if (recipients.isEmpty()) return
        val pushSender = if (sealedSender) SealedSenderDelivery.REDACTED_SENDER else senderId
        recipients.forEach { recipientId ->
            enqueueDelivery(
                Delivery(
                    recipientId = recipientId,
                    isCall = false,
                    data = buildMap {
                        put("type", "NEW_MESSAGE")
                        put("chatId", chatId)
                        put("messageId", messageId)
                        put("senderId", pushSender)
                        put("messageType", messageType)
                        put("recipientId", recipientId)
                        if (sealedSender) put("sealedSender", "1")
                    }
                )
            )
        }
    }

    fun enqueueIncomingCall(recipientId: String, senderId: String, isVideo: Boolean, callId: String = "") {
        if (closed.get() || !isConfigured || recipientId == senderId) return
        enqueueDelivery(
            Delivery(
                recipientId = recipientId,
                isCall = true,
                data = buildMap {
                    put("type", "INCOMING_CALL")
                    put("senderId", senderId)
                    put("callType", if (isVideo) "VIDEO" else "AUDIO")
                    put("recipientId", recipientId)
                    if (callId.isNotBlank()) put("callId", callId)
                }
            )
        )
    }

    fun enqueuePostInteraction(recipientId: String, actorId: String, postId: String, interaction: String, preview: String? = null, commentId: String? = null) {
        if (closed.get() || !isConfigured || recipientId == actorId || interaction !in setOf("LIKE", "COMMENT", "COMMENT_LIKE", "REPLY")) return
        val data = mutableMapOf(
            "type" to "POST_INTERACTION",
            "postId" to postId,
            "actorId" to actorId,
            "interaction" to interaction,
            "recipientId" to recipientId
        )
        // 1.130：评论/回复/评论赞附内容预览（截断，防大文）
        preview?.takeIf(String::isNotBlank)?.let { data["preview"] = it.trim().take(80) }
        // 1.132：附评论 id，App 打开动态时可跳转到该评论
        commentId?.takeIf(String::isNotBlank)?.let { data["commentId"] = it }
        enqueueDelivery(
            Delivery(
                recipientId = recipientId,
                isCall = false,
                data = data
            )
        )
    }

    /** Group invite wake — routing metadata only, no group name or message body. */
    fun enqueueGroupInvite(
        recipientId: String,
        fromUserId: String,
        inviteId: String,
        chatId: String,
        action: String
    ) {
        if (closed.get() || !isConfigured || recipientId.isBlank() || recipientId == fromUserId) return
        if (action != "CREATED") return
        if (inviteId.isBlank() || chatId.isBlank()) return
        enqueueDelivery(
            Delivery(
                recipientId = recipientId,
                isCall = false,
                data = mapOf(
                    "type" to "GROUP_INVITE",
                    "action" to action,
                    "inviteId" to inviteId,
                    "chatId" to chatId,
                    "fromUserId" to fromUserId,
                    "recipientId" to recipientId
                )
            )
        )
    }

    /** Friend request / accept wake — routing only, no free-text verification message. */
    fun enqueueFriendRequest(recipientId: String, fromUserId: String, requestId: String, action: String) {
        if (closed.get() || !isConfigured || recipientId.isBlank() || recipientId == fromUserId) return
        if (action !in setOf("CREATED", "ACCEPTED")) return
        enqueueDelivery(
            Delivery(
                recipientId = recipientId,
                isCall = false,
                data = mapOf(
                    "type" to "FRIEND_REQUEST",
                    "action" to action,
                    "requestId" to requestId,
                    "fromUserId" to fromUserId,
                    "recipientId" to recipientId
                )
            )
        )
    }

    /** 系统公告推送（仅高优先级：EMERGENCY / MAINTENANCE）。App 端拉取详情并展示。 */
    fun enqueueAnnouncement(recipientId: String, announcementId: String, title: String, level: String) {
        if (closed.get() || !isConfigured || recipientId.isBlank() || announcementId.isBlank()) return
        enqueueDelivery(
            Delivery(
                recipientId = recipientId,
                isCall = false,
                // 紧急公告突破 DND（维护/紧急通知语义）
                breakthroughDnd = level == "EMERGENCY",
                data = mapOf(
                    "type" to "ANNOUNCEMENT",
                    "announcementId" to announcementId,
                    "title" to title,
                    "level" to level,
                    "recipientId" to recipientId
                )
            )
        )
    }

    private fun enqueueDelivery(delivery: Delivery) {
        if (closed.get()) return
        val queue = if (delivery.isCall) callDeliveries else deliveries
        if (queue.trySend(delivery).isSuccess) return
        val counter = if (delivery.isCall) droppedCallDeliveries else droppedDeliveries
        val dropped = counter.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            logger.warn(
                "FCM {} delivery queue full; dropped {} deliveries",
                if (delivery.isCall) "call" else "regular",
                dropped
            )
        }
    }

    private suspend fun deliverToUser(recipientId: String, isCall: Boolean, data: Map<String, String>, breakthroughDnd: Boolean = false) {
        runCatching {
            val settings = preferenceRepository.getSettings(recipientId)
            if (!settings.enableNotifications) return
            pushTokenRepository.getForUser(recipientId).forEach { record ->
                // Incoming calls break through DND (client + common IM UX); messages/posts stay quiet.
                // EMERGENCY announcements also break through (breakthroughDnd).
                // 8.37：时区偏移是注册时上报的——跨时区旅行后已过期，按旧时区静默会永久丢通知
                //（客户端本地 DND 才是权威）。注册时间超过 14 天的 token 跳过服务端 DND（fail-open），
                // 客户端本地判定兜底；fresh token 仍走服务端 DND（省 FCM 配额）。
                val tzStale = record.updatedAt > 0L &&
                    System.currentTimeMillis() - record.updatedAt > TIMEZONE_FRESHNESS_MS
                val quiet = !isCall && !breakthroughDnd &&
                    !tzStale &&
                    isInDoNotDisturb(settings, record.timezoneOffsetMinutes)
                if (!quiet) {
                    send(record, data + ("soundEnabled" to soundEnabled(settings, isCall).toString()))
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            logger.warn("FCM delivery preparation failed for user {}", recipientId, error)
        }
    }

    /** 8.48 修复 H8：批量投递一批 delivery，共享一次 settings/tokens 批量查询。 */
    private suspend fun deliverBatch(batch: List<Delivery>, isCall: Boolean) {
        runCatching {
            val recipientIds = batch.map { it.recipientId }.distinct()
            val settingsByUser = preferenceRepository.getSettingsBatch(recipientIds)
            val tokensByUser = pushTokenRepository.getForUsers(recipientIds)
            for (delivery in batch) {
                val settings = settingsByUser[delivery.recipientId] ?: continue
                if (!settings.enableNotifications) continue
                (tokensByUser[delivery.recipientId] ?: emptyList()).forEach { record ->
                    val tzStale = record.updatedAt > 0L &&
                        System.currentTimeMillis() - record.updatedAt > TIMEZONE_FRESHNESS_MS
                    val quiet = !delivery.isCall && !delivery.breakthroughDnd &&
                        !tzStale &&
                        isInDoNotDisturb(settings, record.timezoneOffsetMinutes)
                    if (!quiet) {
                        send(record, delivery.data + ("soundEnabled" to soundEnabled(settings, delivery.isCall).toString()))
                    }
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            logger.warn("FCM batch delivery failed ({} users)", batch.size, error)
        }
    }

    private suspend fun send(record: PushTokenRecord, data: Map<String, String>) {
        val accessToken = getAccessToken() ?: return
        val signed = signPayload(record.userId, data)
        val httpClient = synchronized(clientLock) {
            if (closed.get()) null else clientDelegate.value
        } ?: return
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("token", record.token)
                put("data", buildJsonObject { signed.forEach { (key, value) -> put(key, value) } })
                put("android", buildJsonObject {
                    put("priority", "HIGH")
                    // TTL 60s 会让 Doze/弱网设备的延迟投递被 FCM 直接丢弃 → 通知永久丢失。
                    // 提到 24h：客户端 WS 断开期间的推送可等设备唤醒后补达（消息正文仍可
                    // 由打开聊天时拉取；tray 通知以 FCM 为最终兜底）。
                    put("ttl", "86400s")
                })
            })
        }.toString()

        // 8.31 运维修复：瞬态失败（429/5xx/网络错误）重试一次（1s 退避），
        // 防 FCM 瞬时故障 = 通知永久丢失；永久错误（400/401 类）直接清理 token。
        var transientFailure = false
        runCatching {
            val response = httpClient.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
                bearerAuth(accessToken)
                header("Content-Type", "application/json")
                setBody(body)
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                if (isPermanentTokenFailure(responseBody)) {
                    pushTokenRepository.removeToken(record.token)
                    logger.info("Removed invalid FCM token for device {}", record.deviceId)
                } else if (response.status.value == 429 || response.status.value >= 500) {
                    transientFailure = true
                } else {
                    logger.warn("FCM returned {} for device {}: {}", response.status.value, record.deviceId, responseBody.take(500))
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // 网络/超时类异常视为瞬态，走重试
            transientFailure = true
            logger.warn("FCM request failed for device {}", record.deviceId, error)
        }
        if (transientFailure) {
            kotlinx.coroutines.delay(1_000)
            runCatching {
                val retry = httpClient.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
                    bearerAuth(accessToken)
                    header("Content-Type", "application/json")
                    setBody(body)
                }
                val retryBody = retry.bodyAsText()
                if (!retry.status.isSuccess()) {
                    if (isPermanentTokenFailure(retryBody)) {
                        pushTokenRepository.removeToken(record.token)
                        logger.info("Removed invalid FCM token for device {}", record.deviceId)
                    } else {
                        logger.warn("FCM retry returned {} for device {}: {}", retry.status.value, record.deviceId, retryBody.take(500))
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                logger.warn("FCM retry failed for device {}", record.deviceId, error)
            }
        }
    }

    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(credentials) {
                val current = credentials.accessToken
                if (current != null && (current.expirationTime?.time ?: 0L) > System.currentTimeMillis() + 60_000L) {
                    current.tokenValue
                } else {
                    credentials.refreshAccessToken().tokenValue
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            logger.warn("Unable to obtain FCM OAuth access token", error)
            null
        }
    }

    private fun isInDoNotDisturb(settings: NotificationSettingsResponse, offsetMinutes: Int): Boolean {
        if (!settings.dndEnabled) return false
        val start = settings.dndStartMinute.coerceIn(0, 1439)
        val end = settings.dndEndMinute.coerceIn(0, 1439)
        if (start == end) return false
        val safeOffset = offsetMinutes.coerceIn(-18 * 60, 18 * 60)
        val local = Instant.now().atOffset(ZoneOffset.ofTotalSeconds(safeOffset * 60))
        val minute = local.hour * 60 + local.minute
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    private fun soundEnabled(settings: NotificationSettingsResponse, isCall: Boolean): Boolean {
        return if (isCall) settings.ringtoneEnabled else settings.soundEnabled
    }

    private fun isPermanentTokenFailure(body: String): Boolean {
        return body.contains("UNREGISTERED", ignoreCase = true) ||
            body.contains("registration-token-not-registered", ignoreCase = true)
    }

    companion object {
        const val DELIVERY_QUEUE_CAPACITY = 1_024
        const val DELIVERY_WORKERS = 4
        /** 8.48 修复 H8：批量投递批次大小（每批共享一次批量查询）。 */
        const val DELIVERY_BATCH = 50
        const val CALL_QUEUE_CAPACITY = 256
        const val CALL_DELIVERY_WORKERS = 2
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        /** 推送 token 上报时区超过该时长视为过期：跳过服务端 DND（8.37）。 */
        const val TIMEZONE_FRESHNESS_MS = 14L * 24L * 60L * 60L * 1_000L

        /**
         * 对推送 data 计算 HMAC-SHA256 签名，附加 sig + ts 字段。
         * 规范化：剔除 sig/ts 后按 key 字典序拼接 `k=v` 并以 `&` 连接，再追加 `&ts=<ts>`。
         * 客户端用同一规范化算法 + 同一密钥校验（见客户端 PushVerifyPrefs / MaodouFirebaseMessagingService）。
         *
         * 安全：密钥按接收者派生（HMAC(master, recipientId)），/api/push/verify-key 只对
         * 每个用户下发其自身派生密钥——任一用户只能伪造发给**自己**的推送，无法伪造他人的。
         */
        fun signPayload(recipientId: String, data: Map<String, String>): Map<String, String> {
            val ts = System.currentTimeMillis().toString()
            val base = data.filterKeys { it != "sig" && it != "ts" }
            val canonical = base.keys.sorted().joinToString("&") { "${it}=${base[it]}" }
            val payload = "$canonical&ts=$ts"
            val sig = try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(pushKeyForUser(recipientId).toByteArray(Charsets.UTF_8), "HmacSHA256"))
                mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
            return data + ("ts" to ts) + ("sig" to sig)
        }

        /**
         * 按接收者派生推送校验密钥：HMAC-SHA256(masterSecret, recipientId)。
         * 服务端对每个接收者用其派生密钥签名；客户端经 /api/push/verify-key 取回自己的派生密钥验签。
         */
        fun pushKeyForUser(recipientId: String): String = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ServerConfig.pushHmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal(recipientId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private data class Delivery(
        val recipientId: String,
        val isCall: Boolean,
        val data: Map<String, String>,
        /** 突破 DND（来电/紧急公告等）。 */
        val breakthroughDnd: Boolean = false
    )
}
