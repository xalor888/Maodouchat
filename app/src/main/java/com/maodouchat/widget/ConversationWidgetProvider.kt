package com.maodouchat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.maodouchat.MainActivity
import com.maodouchat.MaodouchatApp
import com.maodouchat.quickreply.ChatGateVerdict
import com.maodouchat.quickreply.QuickReplyPolicy
import com.maodouchat.quickreply.SyncVerdict
import com.maodouchat.util.AppNotifier
import kotlinx.coroutines.launch

/**
 * B5 主屏小组件 Provider。
 *
 * 声明完整：Manifest 中带 APPWIDGET_UPDATE intent-filter 与
 * @xml/conversation_widget_info meta-data（见 AndroidManifest.xml 追加段）。
 *
 * 接收的 intent 协议（见 ConversationWidgetContract）：
 * - ACTION_SYNC_TICK / APPWIDGET_UPDATE：同步渲染；
 * - ACTION_OPEN_CHAT：行点击 → MainActivity 打开会话；
 * - ACTION_MARK_READ：标记会话已读并刷新；
 * - ACTION_REPLY_SENT：RemoteInput 快捷回复（先过 QuickReplyPolicy 门禁再加密发送）。
 */
class ConversationWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // 8.40：只清理「未配置」的全新实例（launcher 重建/桌面刷新投递 APPWIDGET_UPDATE 时，
        // 无条件 removeWidget 会把用户钉住的会话/角标/紧凑配置抹掉）
        appWidgetIds
            .filterNot { ConversationWidgetData.isConfigured(context, it) }
            .forEach { ConversationWidgetData.removeWidget(context, it) }
        ConversationWidgetData.refresh(context, appWidgetIds.toList())
        ConversationWidgetData.startSync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        ConversationWidgetData.refresh(context, listOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 发送者校验：小组件 PendingIntent 由系统投递（sendingUid == 本应用或 system），
        // 第三方应用伪造广播会被拒收（L1 安全加固）。
        // getSendingUid()（API 26-33）在 SDK 36 中已移除，改为 getSentFromUid()（API 34+）；
        // 反射依次尝试，保证两个区间都能拿到发送者 UID。
        val senderUid = runCatching {
            val method = runCatching {
                javaClass.getMethod("getSentFromUid")
            }.getOrElse {
                javaClass.getMethod("getSendingUid")
            }
            method.invoke(this) as? Int
        }.getOrNull()
        // 9.139：无法解析发送者 UID 时必须 fail-closed 拒收——此前回退 myUid()
        // 会让校验恒过，第三方伪造广播在反射失败路径下可绕过发送者校验
        if (senderUid == null || (senderUid != android.os.Process.myUid() && senderUid != android.os.Process.SYSTEM_UID)) {
            return
        }
        when (intent.action) {
            ConversationWidgetContract.ACTION_SYNC_TICK,
            ConversationWidgetContract.ACTION_OPEN_CHAT -> {
                handleOpenOrSync(context, intent)
            }
            ConversationWidgetContract.ACTION_MARK_READ -> {
                handleMarkRead(context, intent)
            }
            ConversationWidgetContract.ACTION_REPLY_SENT -> {
                handleReplySent(context, intent)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ConversationWidgetData.removeWidget(context, it) }
        // 最后一个实例删除时停止周期同步
        if (ConversationWidgetData.allWidgetIds(context).isEmpty()) {
            ConversationWidgetData.stopSync(context)
        }
    }

    override fun onDisabled(context: Context) {
        ConversationWidgetData.stopSync(context)
    }

    private fun handleOpenOrSync(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(ConversationWidgetContract.EXTRA_CHAT_ID).orEmpty()
        val ownerUserId = intent.getStringExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID).orEmpty()
        if (chatId.isNotBlank()) {
            // 账号归属校验（与通知点击同一套策略）
            if (!com.maodouchat.notification.NotificationIntentPolicy.belongsToCurrentAccount(
                    notificationOwnerUserId = ownerUserId,
                    currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
                    sessionPurgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
                )
            ) {
                return
            }
            val open = Intent(context, MainActivity::class.java).apply {
                // 8.40：补 FLAG_ACTIVITY_NEW_TASK——无前台 Activity 的广播/后台进程上下文下，
                // 缺 NEW_TASK 会抛 AndroidRuntimeException 被 runCatching 吞掉，点击小组件无反应
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(AppNotifier.EXTRA_OPEN_CHAT_ID, chatId)
                putExtra(AppNotifier.EXTRA_NOTIFICATION_OWNER_USER_ID, ownerUserId)
                data = Uri.parse("maodouchat-widget://open/$chatId")
            }
            val pi = PendingIntent.getActivity(
                context,
                intent.getStringExtra(ConversationWidgetContract.EXTRA_CHAT_ID)?.hashCode() ?: 0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { pi.send() }.onFailure {
                android.util.Log.w("ConversationWidgetProvider", "open chat broadcast failed", it)
            }
        } else {
            ConversationWidgetData.refreshAll(context)
        }
    }

    private fun handleMarkRead(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(ConversationWidgetContract.EXTRA_CHAT_ID).orEmpty()
        val ownerUserId = intent.getStringExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID).orEmpty()
        if (chatId.isBlank()) return
        // 账号归属校验：旧账号残留 widget 不得用当前账号 token 标记任意会话已读。
        if (!com.maodouchat.notification.NotificationIntentPolicy.belongsToCurrentAccount(
                notificationOwnerUserId = ownerUserId,
                currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
                sessionPurgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
            )
        ) {
            return
        }
        val app = context.applicationContext as? MaodouchatApp ?: return
        app.applicationScope.launch {
            try {
                // 先同步服务端，避免“只清本地角标、换设备/重同步后未读复活”的历史问题。
                val tokenManager = com.maodouchat.network.TokenManager.getInstance(app)
                val ownerUserId = tokenManager.getUserId().orEmpty()
                val token = tokenManager.getToken().orEmpty()
                if (ownerUserId.isNotBlank() && token.isNotBlank() &&
                    com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    val isSecret = runCatching { app.database.chatDao().isSecretChat(chatId) }.getOrDefault(false)
                    val serverResult = if (isSecret) {
                        com.maodouchat.network.ApiService.armSecretChatExpiry(token, chatId)
                    } else {
                        com.maodouchat.network.ApiService.markAllAsRead(token, chatId)
                    }
                    if (serverResult.isFailure) {
                        android.util.Log.w("ConversationWidgetProvider", "widget mark-read server sync failed", serverResult.exceptionOrNull())
                    }
                }
                val chatRepo = com.maodouchat.data.repository.ChatRepository(app.database.chatDao(), app.database.userDao())
                chatRepo.markChatRead(chatId)
                MaodouchatApp.emitChatRead(chatId)
                AppNotifier.cancelMessage(app, chatId)
                ConversationWidgetData.refreshAll(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默失败
            }
        }
    }

    private fun handleReplySent(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(ConversationWidgetContract.EXTRA_CHAT_ID).orEmpty()
        val widgetOwnerUserId = intent.getStringExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID).orEmpty()
        // 账号归属校验：旧账号残留 widget 不得用当前账号 token 发快捷回复。
        if (!com.maodouchat.notification.NotificationIntentPolicy.belongsToCurrentAccount(
                notificationOwnerUserId = widgetOwnerUserId,
                currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId(),
                sessionPurgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
            )
        ) {
            return
        }
        val rawText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ConversationWidgetContract.EXTRA_REPLY_TEXT)
            ?.toString()
        val verdict = QuickReplyPolicy.canAttemptReply(context, chatId, rawText)
        if (verdict !is SyncVerdict.Allowed) {
            toast(context, com.maodouchat.R.string.quick_reply_rejected)
            return
        }
        val app = context.applicationContext as? MaodouchatApp ?: return
        val ownerUserId = verdict.ownerUserId
        val text = verdict.text
        app.applicationScope.launch {
            try {
                val gate = QuickReplyPolicy.gateForChat(app, chatId, ownerUserId)
                if (gate != ChatGateVerdict.Allowed) {
                    toast(app, com.maodouchat.R.string.quick_reply_rejected)
                    return@launch
                }
                if (QuickReplyPolicy.shouldSuppressDuplicate(app, ownerUserId, chatId, text)) {
                    // 系统重复投递：静默丢弃
                    return@launch
                }
                // 8.49 修复：去重键改为发送成功后记录（此前发送前记账，真实失败后的
                // 5 秒重试被误判为重复而静默丢弃）；失败时给出提示而非无声无息
                if (ConversationQuickReplySender.sendQuickReply(
                        app = app,
                        chatId = chatId,
                        text = text,
                        ownerUserId = ownerUserId,
                    )
                ) {
                    QuickReplyPolicy.rememberSent(app, ownerUserId, chatId, text)
                } else {
                    toast(app, com.maodouchat.R.string.quick_reply_rejected)
                }
                ConversationWidgetData.refreshAll(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 发送路径异常：不打扰用户，下次同步可见失败行
            }
        }
    }

    private fun toast(context: Context, resId: Int) {
        runCatching {
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }
}
