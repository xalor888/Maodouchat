package com.maodouchat.push

import com.maodouchat.util.RuntimeFlags
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.notification.NotificationSoundPolicy
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.SecureSessionManager
import com.maodouchat.util.AppNotifier
import com.maodouchat.util.AppLocaleManager
import com.maodouchat.util.PushVerifyPrefs
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Calendar

class MaodouFirebaseMessagingService : FirebaseMessagingService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushRegistrationManager.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val tokenManager = TokenManager.getInstance(this)
        val data = message.data
        val pushOwnerUserId = tokenManager.getUserId().orEmpty()
        if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
        // 服务端对推送负载做了 HMAC 签名；能取到密钥则本地校验，拒绝伪造/重放推送。
        // 密钥缺失或旧服务端未签名时 fail-open，保证现有安装仍可正常收推送。
        if (!verifyPushSignature(data)) return
        when (data["type"]) {
            "NEW_MESSAGE" -> {
                // Messages honor global-off + DND quiet hours.
                if (shouldSuppressQuietHours()) return
                val chatId = PushNotificationPolicy.resolveChatId(data["chatId"]) ?: return
                // Room mute (and active chat / SK_DIST) — server filters too; client is defense-in-depth.
                // Do not use runCatching around runBlocking: it would swallow CancellationException.
                val muted = try {
                    val app = applicationContext as? MaodouchatApp
                    if (app == null) {
                        null
                    } else {
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            app.database.chatDao().getChatById(chatId)?.notificationsMuted
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                // runBlocking can outlive logout/switch — re-check before tray paint.
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                // 8.46：会话级免打扰时段——本地 per-chat 静音窗内不弹通知
                if (chatId.isNotBlank() &&
                    com.maodouchat.notification.ChatQuietHoursPolicy.shouldSuppress(
                        com.maodouchat.notification.ChatQuietHoursStore.get(this, chatId),
                        com.maodouchat.notification.ChatQuietHoursPolicy.currentMinute()
                    )
                ) {
                    return
                }
                // 1.28：临时静音至（silentUntil）窗口内不弹通知——与 WS/BacklogSync 路径一致
                if (chatId.isNotBlank() &&
                    com.maodouchat.notification.ChatQuietHoursStore.silentUntil(this, chatId) > System.currentTimeMillis()
                ) {
                    return
                }
                if (!PushNotificationPolicy.shouldShowNewMessage(
                        messageTypeWire = data["messageType"],
                        chatId = chatId,
                        activeChatId = MaodouchatApp.activeChatId,
                        chatNotificationsMuted = muted
                    )
                ) {
                    return
                }
                val messageId = data["messageId"]?.takeIf(String::isNotBlank)
                    ?: message.messageId
                    ?: "push_${System.currentTimeMillis()}"
                // 8.32 修复 F7：FCM 到达时若消息已在本机 Room（WS 先投递且用户已读），
                // 不再重复弹通知（与 WS 路径的 existingSameMessage 去重对齐）。
                // 8.38：占位 id（push_…）表示「无法判定本地是否已有」——此前被当成「已存在」
                // 导致通知被静默丢弃，方向写反；占位 id 直接照常展示，不做 Room 去重。
                val alreadyLocal = if (messageId.startsWith("push_")) {
                    false
                } else {
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        (applicationContext as? MaodouchatApp)
                            ?.database?.messageDao()?.getMessageById(messageId) != null
                    }
                }
                if (!alreadyLocal) {
                    // 0.72：群聊走独立通知渠道（独立铃声）——本地查会话类型，未知回退单聊渠道
                    val isGroup = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        (applicationContext as? MaodouchatApp)
                            ?.database?.chatDao()?.getChatById(chatId)?.isGroup == true
                    }
                    AppNotifier.showMessage(
                        context = this,
                        chatId = chatId,
                        senderName = getString(R.string.app_name),
                        preview = getString(R.string.notification_encrypted_message),
                        messageId = messageId,
                        soundEnabled = NotificationSoundPolicy.trayMessageSoundEnabled(
                            runtimeFlagEnabled = RuntimeFlags.isEnabled(this, RuntimeFlags.NOTIFICATION_SOUND),
                            userPreferenceEnabled = NotificationPreferences.soundEnabled(this) &&
                                data["soundEnabled"] != "false",
                            chatMuted = muted == true,
                        ),
                        expectedUserId = pushOwnerUserId,
                        isGroup = isGroup,
                    )
                }
            }

            "INCOMING_CALL" -> {
                // Calls break through DND (parity with missed-call tray + common IM UX)
                // but still respect global notifications-off.
                if (!NotificationPreferences.notificationsEnabled(this)) return
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                val senderId = data["senderId"].orEmpty()
                val callId = data["callId"]?.takeIf(String::isNotBlank)
                    ?: message.messageId
                    ?: "call_${senderId.ifBlank { "unknown" }}_${System.currentTimeMillis()}"
                AppNotifier.showIncomingCall(
                    context = this,
                    callId = callId,
                    isVideo = data["callType"] == "VIDEO",
                    soundEnabled = NotificationSoundPolicy.ringtoneEnabled(
                        runtimeFlagEnabled = RuntimeFlags.isEnabled(this, RuntimeFlags.RINGTONE),
                        userPreferenceEnabled = NotificationPreferences.ringtoneEnabled(this) &&
                            data["soundEnabled"] != "false",
                    ),
                    senderId = senderId,
                    expectedUserId = pushOwnerUserId,
                )
            }

            "POST_INTERACTION" -> {
                if (shouldSuppressQuietHours()) return
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                val postId = data["postId"]?.takeIf(String::isNotBlank) ?: return
                AppNotifier.showPostInteraction(
                    context = this,
                    postId = postId,
                    isComment = data["interaction"] == "COMMENT" || data["interaction"] == "COMMENT_LIKE" || data["interaction"] == "REPLY",
                    interaction = data["interaction"].orEmpty().ifBlank { "LIKE" },
                    preview = data["preview"],
                    commentId = data["commentId"],
                    soundEnabled = fcmMessageSoundEnabled(data),
                    expectedUserId = pushOwnerUserId,
                )
            }

            "FRIEND_REQUEST" -> {
                if (shouldSuppressQuietHours()) return
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                val requestId = data["requestId"]?.takeIf(String::isNotBlank)
                    ?: message.messageId
                    ?: "friend_${System.currentTimeMillis()}"
                AppNotifier.showFriendRequest(
                    context = this,
                    requestId = requestId,
                    action = data["action"].orEmpty().ifBlank { "CREATED" },
                    soundEnabled = fcmMessageSoundEnabled(data),
                    expectedUserId = pushOwnerUserId,
                )
            }

            "GROUP_INVITE" -> {
                if (shouldSuppressQuietHours()) return
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                val inviteId = data["inviteId"]?.takeIf(String::isNotBlank)
                    ?: message.messageId
                    ?: return
                AppNotifier.showGroupInvite(
                    context = this,
                    inviteId = inviteId,
                    chatId = PushNotificationPolicy.resolveChatId(data["chatId"]).orEmpty(),
                    action = data["action"].orEmpty().ifBlank { "CREATED" },
                    soundEnabled = fcmMessageSoundEnabled(data),
                    expectedUserId = pushOwnerUserId,
                )
            }

            "ANNOUNCEMENT" -> {
                if (shouldSuppressQuietHours()) return
                if (!mayHandlePush(tokenManager, pushOwnerUserId, data["recipientId"])) return
                val announcementId = data["announcementId"]?.takeIf(String::isNotBlank) ?: return
                val level = data["level"].orEmpty()
                val title = data["title"].orEmpty().ifBlank { "System" }
                AppNotifier.showAnnouncement(
                    context = this,
                    announcementId = announcementId,
                    title = title,
                    level = level,
                    soundEnabled = fcmMessageSoundEnabled(data),
                    expectedUserId = pushOwnerUserId,
                )
            }
        }
    }

    private fun fcmMessageSoundEnabled(data: Map<String, String>): Boolean =
        NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = RuntimeFlags.isEnabled(this, RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = NotificationPreferences.soundEnabled(this),
        ) && data["soundEnabled"] != "false"

    private fun mayHandlePush(
        tokenManager: TokenManager,
        expectedUserId: String,
        payloadRecipientId: String?,
    ): Boolean {
        if (SecureSessionManager.isPurgeInProgress()) return false
        val liveUserId = tokenManager.getUserId()
        if (!PushNotificationPolicy.isAddressedToCurrentUser(payloadRecipientId, liveUserId)) return false
        return BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = liveUserId,
        )
    }

    private fun shouldSuppressQuietHours(): Boolean {
        val now = Calendar.getInstance()
        return com.maodouchat.notification.LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = NotificationPreferences.notificationsEnabled(this),
            dndStartHour = NotificationPreferences.dndStartHour(this),
            dndEndHour = NotificationPreferences.dndEndHour(this),
            hourOfDay = now.get(Calendar.HOUR_OF_DAY),
            dndRuntimeEnabled = RuntimeFlags.isEnabled(this, RuntimeFlags.DND),
            dndEnabled = NotificationPreferences.dndEnabled(this),
            startMinute = NotificationPreferences.dndStartMinute(this),
            endMinute = NotificationPreferences.dndEndMinute(this),
            currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        )
    }

    /**
     * 本地校验推送 HMAC 签名（与服务端 [FcmPushService.signPayload] 同一规范化算法 + 密钥）。
     * @return true = 通过校验（或应当 fail-open 放行）；false = 签名缺失/失效/过期，拒绝该推送。
     */
    private fun verifyPushSignature(data: Map<String, String>): Boolean {
        // 密钥缺失时一律 fail-closed：签名是防伪造推送的唯一防线，
        // 未取得 /api/public/status 下发的密钥前绝不信任任何 FCM data 消息。
        // 应用启动会先拉取 public/status 写入 PushVerifyPrefs，正常推送不受影响。
        val key = PushVerifyPrefs.getKey(this) ?: return false
        val sig = data["sig"] ?: return false
        val ts = data["ts"]?.toLongOrNull() ?: return false
        // 重放/过期保护：签名时间戳须在有效窗口内。窗口与服务端 FCM TTL（24h）对齐——
        // Doze 设备可能延迟数小时送达，±5 分钟会把预期内的延迟推送全部误拒（8.35 CRITICAL）。
        // 重放防护由消息 ID 去重（Room 已存在检查）与来电 callId 幂等兜底。
        val now = System.currentTimeMillis()
        val MAX_SIGNATURE_AGE_MS = 24L * 60L * 60L * 1_000L
        if (ts < now - MAX_SIGNATURE_AGE_MS || ts > now + 5 * 60_000L) return false
        val base = data.filterKeys { it != "sig" && it != "ts" }
        val canonical = base.keys.sorted().joinToString("&") { "${it}=${base[it]}" }
        val payload = "$canonical&ts=$ts"
        // Key is present here (null-key fail-open handled above), so a crypto error means we
        // cannot validate the signature — reject rather than let a malformed push through.
        val expected = runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return false
        return runCatching {
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), sig.toByteArray(Charsets.UTF_8))
        }.getOrDefault(false)
    }
}
