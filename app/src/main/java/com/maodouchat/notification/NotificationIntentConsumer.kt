package com.maodouchat.notification

import android.content.Context
import android.content.Intent
import com.maodouchat.IncomingCallWake
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.telecom.TelecomHelper
import com.maodouchat.ui.navigation.AppLinkAuthGate
import com.maodouchat.ui.navigation.AppLinkDestination
import com.maodouchat.ui.navigation.AppLinkParseResult
import com.maodouchat.ui.navigation.AppLinkRouter
import com.maodouchat.ui.navigation.NotificationTarget

/**
 * 系统入口 Intent 消费（P08：自 `MainActivity` 逐字迁出）。
 *
 * 覆盖外部深链、Telecom 接听/拉起、通知托盘三路入口；全部安全注释（8.34/8.41/8.49/8.56）
 * 随逻辑原样保留。Activity 只提供宿主依赖（上下文、调用方身份、目标槽位、锁屏旗标）。
 */
class NotificationIntentConsumer(
    private val appContext: Context,
    private val packageName: String,
    private val tokenManager: TokenManager,
    private val setTarget: (NotificationTarget?) -> Unit,
    private val applyCallLockScreenFlags: (Boolean) -> Unit,
) {
    fun consume(
        intent: Intent,
        callingPackage: String?,
        callingActivityPackage: String?,
    ) {
        // Caller identity is unavailable for some Android Telecom launches, so null must not be
        // trusted. Notification extras and Telecom actions are accepted only from this app's own
        // pending-call state; external ACTION_VIEW deep links are separately handled below.
        val caller = callingPackage ?: callingActivityPackage
        if (caller != null && caller != packageName) {
            NotificationIntents.clearFrom(intent)
            // 8.34 修复：外部调用者的合法 ACTION_VIEW 深链必须放行——浏览器/系统 resolver
            // 打开 chat.mdou.me/u/{username} 或 maodouchat://u/{username} 时 callingPackage
            // 恒为外部包，此前直接 return 导致 manifest BROWSABLE 外部深链 100% 失效。
            // 通知 extra 已清（防注入面），深链数据继续走下方白名单字符校验；非 VIEW 仍丢弃。
            if (intent.action != android.content.Intent.ACTION_VIEW || intent.data == null) {
                intent.data = null
                return
            }
        }
        // 深链接统一走 AppLinkRouter（P08）：maodouchat://u/<username> 或
        // https://chat.mdou.me/u/<username> → 公开资料页。白名单 scheme/host 与
        // 用户名清洗规则收敛一处（见 AppLinkRouter 及 parity 单测）。
        val data = intent.data
        if (intent.action == android.content.Intent.ACTION_VIEW && data != null) {
            when (val parsed = AppLinkRouter.parseDeepLink(data.toString())) {
                is AppLinkParseResult.Accepted -> {
                    val dest = parsed.destination
                    // P08：业务导航前先过认证门闩；未登录时仍入队 pending，由 MainActivity 等登录后回放。
                    AppLinkAuthGate.evaluate(
                        destination = dest,
                        isLoggedIn = tokenManager.isLoggedIn(),
                    )
                    val ownerUserId = tokenManager.getUserId().orEmpty()
                    val sessionGen = MaodouchatApp.currentSessionGeneration()
                    val target: NotificationTarget? = when (dest) {
                        is AppLinkDestination.PublicProfile -> NotificationTarget.PublicProfile(
                            username = dest.username,
                            sessionGeneration = sessionGen,
                            ownerUserId = ownerUserId,
                        )
                        is AppLinkDestination.ChatDetail -> NotificationTarget.Chat(
                            id = dest.chatId,
                            sessionGeneration = sessionGen,
                            ownerUserId = ownerUserId,
                            messageId = dest.messageId,
                        )
                        is AppLinkDestination.PostDetail -> NotificationTarget.Post(
                            id = dest.postId,
                            sessionGeneration = sessionGen,
                            ownerUserId = ownerUserId,
                        )
                        is AppLinkDestination.AiTasksChat -> NotificationTarget.AiTasks(
                            chatId = dest.chatId,
                            sessionGeneration = sessionGen,
                            ownerUserId = ownerUserId,
                        )
                        is AppLinkDestination.GroupInvite -> NotificationTarget.GroupInvite(
                            code = dest.code,
                            sessionGeneration = sessionGen,
                            ownerUserId = ownerUserId,
                        )
                        // Tab-only destinations still need dedicated targets (contacts/missed/invites tray).
                        else -> null
                    }
                    if (target != null) {
                        setTarget(target)
                        intent.data = null
                        return
                    }
                }
                is AppLinkParseResult.Rejected -> Unit
            }
        }
        // ConnectionService transport actions have no reliable calling package on modern Android.
        // Treat a null caller as untrusted and accept only an action matching an app-owned pending
        // call. The pending call is established by the in-process signaling/Telecom pipeline and
        // cannot be forged by an external explicit Intent.
        val telecomAction = intent.action
        val telecomCallId = intent.getStringExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_CALL_ID).orEmpty()
        val isTelecomAction = telecomAction == TelecomHelper.ACTION_ANSWER_CALL || telecomAction == TelecomHelper.ACTION_INCOMING_CALL
        if (isTelecomAction && !com.maodouchat.telecom.TelecomHelper.isTrustedTransport(telecomCallId)) {
            com.maodouchat.telecom.TelecomHelper.clearExtras(intent)
            return
        }
        if (isTelecomAction) {
            applyCallLockScreenFlags(true)
            // P08：Telecom 唤醒 callId 同样经严格清洗（非法值按空串走通用轮询，不定向响铃）。
            val wakeTelecomCallId = AppLinkRouter.sanitizeCallIdStrict(telecomCallId).orEmpty()
            if (wakeTelecomCallId.isNotBlank()) {
                CallNotificationService.cancelIncomingCall(appContext, wakeTelecomCallId)
            }
            MaodouchatApp.emitIncomingCallWake(
                IncomingCallWake(
                    callId = wakeTelecomCallId,
                    senderId = "",
                    isVideo = intent.getBooleanExtra(com.maodouchat.telecom.TelecomHelper.EXTRA_IS_VIDEO, false),
                    // 8.56：系统 Telecom「接听」≠「来电拉起」——标记自动接听，应用内不再要求二次点击
                    autoAnswer = telecomAction == TelecomHelper.ACTION_ANSWER_CALL,
                )
            )
            com.maodouchat.telecom.TelecomHelper.clearExtras(intent)
            NotificationIntents.clearFrom(intent)
        }
        val rawChatId = intent.getStringExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID)?.takeIf(String::isNotBlank)
        // 8.41：消息「稍后提醒」点击 → 打开聊天后高亮原消息
        val rawMessageId = intent.getStringExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID)?.takeIf(String::isNotBlank)
        val rawAiTasksChatId = intent.getStringExtra(NotificationIntents.EXTRA_OPEN_AI_TASKS_CHAT_ID)?.takeIf(String::isNotBlank)
        val rawPostId = intent.getStringExtra(NotificationIntents.EXTRA_OPEN_POST_ID)?.takeIf(String::isNotBlank)
        // P08：通知/Widget 入口 ID 经 AppLinkRouter 严格清洗（含 /?# 直接拒收；
        // 合法 UUID/服务端 ID 不受影响）。messageId 非法时仅丢弃高亮、仍打开会话。
        val chatId = rawChatId?.let { AppLinkRouter.sanitizeChatIdStrict(it) }
        val messageId = rawMessageId?.let { AppLinkRouter.sanitizeMessageIdStrict(it) }
        val aiTasksChatId = rawAiTasksChatId?.let { AppLinkRouter.sanitizeChatIdStrict(it) }
        val postId = rawPostId?.let { AppLinkRouter.sanitizePostIdStrict(it) }
        val openIncomingCall = intent.getBooleanExtra(NotificationIntents.EXTRA_OPEN_INCOMING_CALL, false)
        val openMissedCalls = intent.getBooleanExtra(NotificationIntents.EXTRA_OPEN_MISSED_CALL, false)
        val openContacts = intent.getBooleanExtra(NotificationIntents.EXTRA_OPEN_CONTACTS, false)
        val notificationOwnerUserId = intent
            .getStringExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
        val hasNotificationTarget = chatId != null || aiTasksChatId != null || postId != null ||
            openIncomingCall || openMissedCalls || openContacts
        if (
            hasNotificationTarget &&
            !com.maodouchat.notification.NotificationIntentPolicy.belongsToCurrentAccount(
                notificationOwnerUserId = notificationOwnerUserId,
                currentUserId = tokenManager.getUserId(),
                sessionPurgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
            )
        ) {
            setTarget(null)
            NotificationIntents.clearFrom(intent)
            return
        }
        if (openIncomingCall) {
            // FCM payload has no SDP — wake observer to poll /api/signaling/pending?offersOnly=true
            // Lock-screen / full-screen intent: keep screen on while user answers.
            // Cleared when CallForegroundService stops (see observeCallLockScreenFlags).
            applyCallLockScreenFlags(true)
            // P08：FCM 唤醒 callId/senderId 同样经严格清洗（非法值按空串走通用轮询）。
            val wakeCallId = AppLinkRouter.sanitizeCallIdStrict(
                intent.getStringExtra(NotificationIntents.EXTRA_INCOMING_CALL_ID).orEmpty()
            ).orEmpty()
            // Ongoing FCM call trays often ignore autoCancel; drop shade entry as soon as
            // the user opened the app for this call (poll / CallScreen still proceed).
            if (wakeCallId.isNotBlank()) {
                CallNotificationService.cancelIncomingCall(appContext, wakeCallId)
            }
            MaodouchatApp.emitIncomingCallWake(
                IncomingCallWake(
                    callId = wakeCallId,
                    senderId = AppLinkRouter.sanitizeUserIdStrict(
                        intent.getStringExtra(NotificationIntents.EXTRA_INCOMING_CALL_SENDER_ID).orEmpty()
                    ).orEmpty(),
                    isVideo = intent.getBooleanExtra(NotificationIntents.EXTRA_INCOMING_CALL_VIDEO, false),
                )
            )
        }
        if (openMissedCalls) {
            // Tray autoCancel is unreliable for some OEMs; clear shade before list marks read.
            // Without a specific callId, cancelAll is too broad — ChatList markMissedCallsRead
            // cancels per-id; here we only emit open (per-id cancel happens after list loads).
            MaodouchatApp.emitOpenMissedCalls()
        }
        if (openContacts) {
            SocialNotificationService.cancelAllFriendRequests(appContext)
            SocialNotificationService.cancelAllGroupInvites(appContext)
            MaodouchatApp.emitOpenContacts()
        }
        // Drop tray immediately on tap so badge/shade clear before the target screen mounts.
        // Center mark-read still happens in ChatDetail / AiTasks / PostDetail screens.
        val sessionGen = MaodouchatApp.currentSessionGeneration()
        when {
            aiTasksChatId != null -> {
                ReminderNotificationService.cancelAiTaskRemindersForChat(appContext, aiTasksChatId)
                setTarget(NotificationTarget.AiTasks(aiTasksChatId, sessionGen, notificationOwnerUserId.orEmpty()))
            }
            chatId != null -> {
                MessageNotificationService.cancelMessage(appContext, chatId)
                setTarget(NotificationTarget.Chat(chatId, sessionGen, notificationOwnerUserId.orEmpty(), messageId))
            }
            postId != null -> {
                SocialNotificationService.cancelPostInteraction(appContext, postId)
                setTarget(NotificationTarget.Post(postId, sessionGen, notificationOwnerUserId.orEmpty()))
            }
            else -> setTarget(null)
        }
        NotificationIntents.clearFrom(intent)
    }
}
