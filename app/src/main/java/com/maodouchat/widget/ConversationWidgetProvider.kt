package com.maodouchat.widget

import com.maodouchat.notification.NotificationIntents
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import com.maodouchat.MainActivity
import com.maodouchat.MaodouchatApp
import com.maodouchat.quickreply.QuickReplyPolicy
import com.maodouchat.quickreply.SyncVerdict

import kotlinx.coroutines.launch

/** `Process.INVALID_UID`（@hide）的字面值：发送方未 opt-in 分享身份时的 sentFromUid。 */
private const val INVALID_UID = -1

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
        // G338：发送者校验（第二道门；第一道是 manifest 的 exported=false）。
        //
        // 实测语义（API 36，判据见 ConversationWidgetProviderInstrumentedTest）：
        // `getSentFromUid()`（API 34+ 取代 getSendingUid）**只在发送方用
        // BroadcastOptions.setShareIdentityEnabled(true) 显式 opt-in 时才携带真实 uid**；
        // 本应用自己的 sendBroadcast / PendingIntent（行点击、标记已读、快捷回复）/
        // AlarmManager 周期同步，实测全部是 INVALID_UID(-1)。旧实现按
        // 「uid 必须等于自己或 system」判，于是把**所有合法投递**也拒了——小组件交互与
        // 周期同步在 API 34+ 上实际全死（G338 由仪器测试发现并修复）。
        //
        // 现在的判据：能解析出的“真实”第三方 uid 一律拒；-1（未 opt-in）与 null（反射失败）
        // 依赖 exported=false 的系统级拦截放行——第三方广播进不来（实测：系统静默丢弃，
        // onReceive 根本不会被调用），合法投递（同 uid / system / PendingIntent 以创建者身份执行）
        // 才到得了这里。
        val senderUid = runCatching {
            val method = runCatching {
                javaClass.getMethod("getSentFromUid")
            }.getOrElse {
                javaClass.getMethod("getSendingUid")
            }
            method.invoke(this) as? Int
        }.getOrNull() ?: INVALID_UID
        val isForeignSender = senderUid != INVALID_UID &&
            senderUid != android.os.Process.myUid() &&
            senderUid != android.os.Process.SYSTEM_UID
        if (isForeignSender) {
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
        // P08：生产侧清洗——非法 ID 直接走刷新分支，不构造 tap intent
        //（消费侧 MainActivity 同样会拒收；此处前置失败，避免坏 data URI 进 PendingIntent）。
        val cleanChatId = com.maodouchat.navigation.AppLinkRouter.sanitizeChatIdStrict(chatId)
        if (cleanChatId != null) {
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
                putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, cleanChatId)
                putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, ownerUserId)
                data = "maodouchat-widget://open/$cleanChatId".toUri()
            }
            val pi = PendingIntent.getActivity(
                context,
                cleanChatId.hashCode(),
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
        // P08：生产侧清洗与打开路径同口径。
        val cleanChatId = com.maodouchat.navigation.AppLinkRouter.sanitizeChatIdStrict(chatId)
            ?: return
        val app = context.applicationContext as? MaodouchatApp ?: return
        app.applicationScope.launchSafe {
            app.conversationReadReceiptCoordinator.markRead(cleanChatId, ownerUserId.ifBlank { null })
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
                val result = com.maodouchat.quickreply.androidQuickReplyCommandHandler(app).execute(
                    com.maodouchat.quickreply.QuickReplyCommand(
                        ownerUserId = ownerUserId,
                        chatId = chatId,
                        text = text,
                    )
                )
                when (result) {
                    com.maodouchat.quickreply.QuickReplyCommandResult.Sent,
                    com.maodouchat.quickreply.QuickReplyCommandResult.Duplicate -> Unit
                    is com.maodouchat.quickreply.QuickReplyCommandResult.Rejected,
                    com.maodouchat.quickreply.QuickReplyCommandResult.Failed -> {
                        toast(app, com.maodouchat.R.string.quick_reply_rejected)
                    }
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
