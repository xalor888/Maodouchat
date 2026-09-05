package com.maodouchat.quickreply

import com.maodouchat.notification.NotificationIntents
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.domain.messaging.QuickReplyRequest
import com.maodouchat.notification.NotificationIntentPolicy
import com.maodouchat.security.SecureSessionManager

import com.maodouchat.widget.launchSafe

/**
 * M11 系统通知 RemoteInput 行内快捷回复与通知动作 Receiver。
 *
 * 职责：
 * 1. 验证系统调用来源与账号隔离（拒绝跨账号/已注销会话的伪造输入）；
 * 2. 提取 RemoteInput 输入并进行基础前置校验；
 * 3. 委派 [com.maodouchat.domain.messaging.QuickReplyUseCase] 入队发送，不直接操作底层存储；
 * 4. 委派 [com.maodouchat.conversation.ConversationReadReceiptCoordinator] 处理通知标记已读动作。
 */
class NotificationQuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_REPLY && action != ACTION_MARK_READ) return

        // 发送者 UID 校验：由系统通知服务或本应用发起
        val senderUid = runCatching {
            val method = runCatching {
                javaClass.getMethod("getSentFromUid")
            }.getOrElse {
                javaClass.getMethod("getSendingUid")
            }
            method.invoke(this) as? Int
        }.getOrNull()

        if (senderUid != null && senderUid != android.os.Process.myUid() && senderUid != android.os.Process.SYSTEM_UID) {
            return
        }

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        val expectedOwnerUserId = intent.getStringExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID).orEmpty()
        val currentUserId = com.maodouchat.network.TokenManager.getInstance(context).getUserId()

        if (!NotificationIntentPolicy.belongsToCurrentAccount(
                notificationOwnerUserId = expectedOwnerUserId,
                currentUserId = currentUserId,
                sessionPurgeInProgress = SecureSessionManager.isPurgeInProgress(),
            )
        ) {
            return
        }

        val app = context.applicationContext as? MaodouchatApp ?: return

        if (action == ACTION_MARK_READ) {
            app.applicationScope.launchSafe {
                app.conversationReadReceiptCoordinator.markRead(chatId, expectedOwnerUserId.ifBlank { null })
            }
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val rawText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val verdict = QuickReplyPolicy.canAttemptReply(context, chatId, rawText)
        if (verdict !is SyncVerdict.Allowed) {
            toast(context, R.string.quick_reply_rejected)
            return
        }

        val text = verdict.text
        val idempotencyKey = QuickReplyPolicy.dedupeKey(verdict.ownerUserId, chatId, text)

        app.applicationScope.launchSafe {
            val result = app.quickReplyUseCase.reply(
                QuickReplyRequest(
                    conversationId = chatId,
                    text = text,
                    idempotencyKey = idempotencyKey,
                )
            )
            result.onFailure {
                toast(app, R.string.quick_reply_rejected)
            }
        }
    }

    private fun toast(context: Context, resId: Int) {
        runCatching {
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_REPLY = "com.maodouchat.action.NOTIFICATION_QUICK_REPLY"
        const val ACTION_MARK_READ = "com.maodouchat.action.NOTIFICATION_MARK_READ"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_CHAT_ID = "extra_chat_id"
    }
}
