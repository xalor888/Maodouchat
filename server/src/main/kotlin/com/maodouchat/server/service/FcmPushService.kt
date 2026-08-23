package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.NotificationSettingsResponse
import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRepository
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

/**
 * Offline wake sender — FCM HTTP v1 has been removed.
 *
 * Real-time delivery is WebSocket `/ws` plus the Ideaura-style client keep-alive
 * (foreground service, daemon resurrection, optional hold-call). Enqueue methods
 * stay as call-site no-ops so routing metadata contracts remain testable without
 * talking to Google.
 */
class FcmPushService(
    @Suppress("unused") private val pushTokenRepository: PushTokenRepository,
    @Suppress("unused") private val preferenceRepository: NotificationPreferenceRepository,
    @Suppress("unused") private val projectId: String = "",
    @Suppress("unused") private val serviceAccountFile: String = "",
    @Suppress("unused") private val nowMillis: () -> Long = { System.currentTimeMillis() },
    @Suppress("unused") private val retryDelayMs: Long = 1_000L,
    @Suppress("unused") private val startWorkers: Boolean = true
) {
    private val logger = LoggerFactory.getLogger(FcmPushService::class.java)
    private val closed = AtomicBoolean(false)
    internal var onQueued: ((Delivery) -> Unit)? = null

    /** FCM is gone; never treat this as a live remote-push channel. */
    val isConfigured: Boolean
        get() = false

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        logger.info("Wake push service shut down (no FCM transport)")
    }

    fun enqueueEncryptedMessage(
        recipientIds: Collection<String>,
        chatId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        sealedSender: Boolean = false
    ) {
        if (closed.get()) return
        if (chatId.isBlank() || messageId.isBlank()) return
        val recipients = recipientIds.asSequence()
            .filter { it.isNotBlank() && it != senderId }
            .distinct()
            .toList()
        if (recipients.isEmpty()) return
        val pushSender = if (sealedSender) SealedSenderDelivery.REDACTED_SENDER else senderId
        recipients.forEach { recipientId ->
            record(
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
        if (closed.get() || recipientId.isBlank() || recipientId == senderId) return
        record(
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

    fun enqueuePostInteraction(
        recipientId: String,
        actorId: String,
        postId: String,
        interaction: String,
        preview: String? = null,
        commentId: String? = null
    ) {
        if (closed.get() || recipientId.isBlank() || postId.isBlank() || recipientId == actorId ||
            interaction !in setOf("LIKE", "COMMENT", "COMMENT_LIKE", "REPLY")
        ) return
        val data = mutableMapOf(
            "type" to "POST_INTERACTION",
            "postId" to postId,
            "actorId" to actorId,
            "interaction" to interaction,
            "recipientId" to recipientId
        )
        preview?.takeIf(String::isNotBlank)?.let { data["preview"] = it.trim().take(80) }
        commentId?.takeIf(String::isNotBlank)?.let { data["commentId"] = it }
        record(Delivery(recipientId = recipientId, isCall = false, data = data))
    }

    fun enqueueGroupInvite(
        recipientId: String,
        fromUserId: String,
        inviteId: String,
        chatId: String,
        action: String
    ) {
        if (closed.get() || recipientId.isBlank() || recipientId == fromUserId) return
        if (action != "CREATED") return
        if (inviteId.isBlank() || chatId.isBlank()) return
        record(
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

    fun enqueueFriendRequest(recipientId: String, fromUserId: String, requestId: String, action: String) {
        if (closed.get() || recipientId.isBlank() || recipientId == fromUserId) return
        if (action !in setOf("CREATED", "ACCEPTED") || requestId.isBlank()) return
        record(
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

    fun enqueueAnnouncement(recipientId: String, announcementId: String, title: String, level: String) {
        if (closed.get() || recipientId.isBlank() || announcementId.isBlank()) return
        record(
            Delivery(
                recipientId = recipientId,
                isCall = false,
                breakthroughDnd = level == "EMERGENCY",
                data = mapOf(
                    "type" to "ANNOUNCEMENT",
                    "announcementId" to announcementId,
                    "title" to title.trim().take(ANNOUNCEMENT_TITLE_MAX),
                    "level" to level,
                    "recipientId" to recipientId
                )
            )
        )
    }

    private fun record(delivery: Delivery) {
        if (closed.get()) return
        onQueued?.invoke(delivery)
    }

    companion object {
        const val DELIVERY_QUEUE_CAPACITY = 1_024
        const val DELIVERY_WORKERS = 4
        const val DELIVERY_BATCH = 50
        const val CALL_QUEUE_CAPACITY = 256
        const val CALL_DELIVERY_WORKERS = 2
        const val TIMEZONE_FRESHNESS_MS = 14L * 24L * 60L * 60L * 1_000L
        const val MESSAGE_TTL = "86400s"
        const val CALL_TTL = "60s"
        const val ANNOUNCEMENT_TITLE_MAX = 80

        fun isTransientHttpFailure(status: Int): Boolean = status == 429 || status >= 500

        fun ttlFor(type: String?): String =
            if (type == "INCOMING_CALL") CALL_TTL else MESSAGE_TTL

        fun isInDoNotDisturb(
            settings: NotificationSettingsResponse,
            offsetMinutes: Int,
            nowMillis: Long
        ): Boolean {
            if (!settings.dndEnabled) return false
            val start = settings.dndStartMinute.coerceIn(0, 1439)
            val end = settings.dndEndMinute.coerceIn(0, 1439)
            if (start == end) return false
            val safeOffset = offsetMinutes.coerceIn(-18 * 60, 18 * 60)
            val local = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.ofTotalSeconds(safeOffset * 60))
            val minute = local.hour * 60 + local.minute
            return if (start < end) minute in start until end else minute >= start || minute < end
        }

        fun isPermanentTokenFailure(status: Int, body: String): Boolean {
            if (status != 400 && status != 404) return false
            val text = body.lowercase()
            if ("unregistered" in text ||
                "not_found" in text ||
                "notfound" in text ||
                "registration-token-not-registered" in text ||
                "requested entity was not found" in text ||
                "sender_id_mismatch" in text ||
                "senderidmismatch" in text
            ) {
                return true
            }
            val invalidArg = "invalid_argument" in text || "invalidargument" in text
            return invalidArg && ("registration" in text || "token" in text)
        }

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

        fun pushKeyForUser(recipientId: String): String = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ServerConfig.pushHmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal(recipientId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    internal data class Delivery(
        val recipientId: String,
        val isCall: Boolean,
        val data: Map<String, String>,
        val breakthroughDnd: Boolean = false
    )
}
