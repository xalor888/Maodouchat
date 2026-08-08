package com.maodouchat.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.RemoteViews
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AppLockManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B5 主屏小组件 · 数据提供者与 RemoteViews 渲染。
 *
 * - 配置（钉住的会话 / 未读角标 / 紧凑）与快照（已脱敏）以 JSON 存 SharedPreferences；
 * - 快照在 IO 线程加载并完成脱敏：密聊会话直接剔除；App 锁开启时标题替换为应用名、
 *   预览替换为通用加密文案、未读角标隐藏；会话 PIN 锁行替换为「已锁定会话」；
 * - 更新由 ConversationWidgetSyncService（AlarmManager 周期）与
 *   requestPushUpdate（去抖后 startService）驱动。
 */
object ConversationWidgetData {

    // ---- 模型 ----
    data class WidgetConfig(
        val chatIds: List<String> = emptyList(),
        val showUnreadBadge: Boolean = true,
        val compact: Boolean = false,
    )

    data class WidgetRow(
        val chatId: String,
        val title: String,
        val subtitle: String,
        val timeLabel: String,
        val unread: Int,
    )

    data class WidgetSnapshot(
        val rows: List<WidgetRow>,
        val totalUnread: Int,
        val ownerUserId: String,
        val signedOut: Boolean,
        val updatedAt: Long,
    )

    // ---- 配置读写 ----
    fun widgetConfig(context: Context, widgetId: Int): WidgetConfig {
        val json = prefs(context).getString(configKey(widgetId), null) ?: return WidgetConfig()
        return decodeConfig(json)
    }

    fun saveConfig(context: Context, widgetId: Int, config: WidgetConfig) {
        // 8.40：保存配置即置「已配置」标记——onUpdate 不再无条件抹掉既有实例配置
        prefs(context).edit()
            .putString(configKey(widgetId), encodeConfig(config))
            .putBoolean(configuredKey(widgetId), true)
            .apply()
    }

    /** 该实例是否已由配置页保存过配置（未配置 = 全新实例，onUpdate 可清理）。 */
    fun isConfigured(context: Context, widgetId: Int): Boolean =
        prefs(context).getBoolean(configuredKey(widgetId), false)

    fun removeWidget(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove(configKey(widgetId))
            .remove(configuredKey(widgetId))
            .apply()
    }

    fun allWidgetIds(context: Context): List<Int> =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            android.content.ComponentName(context, ConversationWidgetProvider::class.java)
        ).toList()

    // ---- 快照加载（IO，含脱敏） ----
    suspend fun loadSnapshot(context: Context, config: WidgetConfig): WidgetSnapshot {
        val tokenManager = TokenManager.getInstance(context)
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken()
        if (ownerUserId.isBlank() || token.isNullOrBlank()) {
            return WidgetSnapshot(rows = emptyList(), totalUnread = 0, ownerUserId = "", signedOut = true, updatedAt = System.currentTimeMillis())
        }
        return withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as? MaodouchatApp
                    ?: return@withContext WidgetSnapshot(emptyList(), 0, ownerUserId, false, System.currentTimeMillis())
                val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
                val secretIds = app.database.secretChatDao().listSecretChatIds().toSet()
                val appLockOn = AppLockManager.isEnabled(context)
                val appName = context.getString(R.string.app_name)
                val genericPreview = context.getString(R.string.notification_encrypted_message)
                val lockedPreview = context.getString(R.string.chat_lock_list_preview)
                val attachmentLabel = context.getString(R.string.widget_attachment_label)
                val rows = mutableListOf<WidgetRow>()
                var totalUnread = 0
                val maxRows = if (config.compact) ConversationWidgetContract.MAX_ROWS_COMPACT else ConversationWidgetContract.MAX_ROWS
                for (chatId in config.chatIds) {
                    if (rows.size >= maxRows) break
                    if (chatId.isBlank() || chatId in secretIds) continue // 密聊：永不显示
                    val chat = chatRepo.getChatById(chatId) ?: continue
                    val pinLocked = app.database.chatLockDao().get(chatId) != null
                    val title = if (appLockOn || pinLocked) {
                        appName
                    } else {
                        chatTitle(chat, ownerUserId) ?: chatId.take(12)
                    }
                    val subtitle = when {
                        pinLocked -> lockedPreview
                        appLockOn -> genericPreview
                        else -> previewOf(chat.lastMessage, chat.lastMessageType, attachmentLabel)
                    }
                    val unread = if (appLockOn || !config.showUnreadBadge) 0 else chat.unreadCount
                    totalUnread += unread
                    rows += WidgetRow(
                        chatId = chatId,
                        title = title,
                        subtitle = subtitle,
                        timeLabel = relativeTime(chat.lastMessageTime),
                        unread = unread,
                    )
                }
                WidgetSnapshot(
                    rows = rows,
                    totalUnread = totalUnread,
                    ownerUserId = ownerUserId,
                    signedOut = false,
                    updatedAt = System.currentTimeMillis(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WidgetSnapshot(emptyList(), 0, ownerUserId, false, System.currentTimeMillis())
            }
        }
    }

    // ---- RemoteViews 渲染 ----
    fun renderViews(
        context: Context,
        config: WidgetConfig,
        snapshot: WidgetSnapshot,
        widgetId: Int,
    ): RemoteViews {
        val layoutId = if (config.compact) R.layout.conversation_widget_compact else R.layout.conversation_widget
        val views = RemoteViews(context.packageName, layoutId)
        views.setTextViewText(R.id.widgetHeaderTitle, context.getString(R.string.widget_header_title))
        val headerUnread = snapshot.totalUnread.coerceAtMost(99)
        views.setTextViewText(R.id.widgetHeaderUnread, if (headerUnread > 0) "$headerUnread" else "")
        views.setViewVisibility(R.id.widgetHeaderUnread, if (headerUnread > 0) android.view.View.VISIBLE else android.view.View.GONE)
        views.removeAllViews(R.id.widgetRowsContainer)

        val maxRows = if (config.compact) ConversationWidgetContract.MAX_ROWS_COMPACT else ConversationWidgetContract.MAX_ROWS
        val rows = snapshot.rows.take(maxRows)
        if (snapshot.signedOut) {
            addRow(context, views, widgetId, rowIndex = 0, widgetRow = null, emptyLabel = context.getString(R.string.widget_signed_out))
        } else if (rows.isEmpty()) {
            views.setViewVisibility(R.id.widgetEmptyHint, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widgetEmptyHint, android.view.View.GONE)
            rows.forEachIndexed { index, row -> addRow(context, views, widgetId, index, row, null) }
        }
        return views
    }

    // ---- 显式刷新 ----
    fun refresh(context: Context, widgetIds: List<Int>) {
        val app = context.applicationContext as? MaodouchatApp ?: return
        app.applicationScope.launchSafe {
            val ids = widgetIds.ifEmpty { allWidgetIds(context) }
            ids.forEach { widgetId ->
                val config = widgetConfig(context, widgetId)
                val snapshot = loadSnapshot(context, config)
                val views = renderViews(context, config, snapshot, widgetId)
                AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
            }
        }
    }

    fun refreshAll(context: Context) = refresh(context, allWidgetIds(context))

    /** 去抖后触发一次后台同步（新消息 / 前台恢复 / 交互后调用） */
    fun requestPushUpdate(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_PUSH_MS, 0L)
        if (now - last < ConversationWidgetContract.PUSH_WINDOW_MS) return
        prefs.edit().putLong(KEY_LAST_PUSH_MS, now).apply()
        val service = Intent(context, ConversationWidgetSyncService::class.java)
        runCatching { context.startService(service) }
    }

    // ---- 周期同步（AlarmManager） ----
    fun startSync(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = syncPendingIntent(context)
        val interval = ConversationWidgetContract.KEY_SYNC_PERIOD_MIN * 60_000L
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, interval, interval, pi)
    }

    fun stopSync(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(syncPendingIntent(context))
    }

    private fun syncPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConversationWidgetProvider::class.java)
            .setAction(ConversationWidgetContract.ACTION_SYNC_TICK)
            .setData(Uri.parse("maodouchat-widget://sync"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ---- 私有 ----
    private fun addRow(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        rowIndex: Int,
        widgetRow: WidgetRow?,
        emptyLabel: String?,
    ) {
        val row = RemoteViews(context.packageName, R.layout.conversation_widget_row)
        if (widgetRow != null) {
            row.setTextViewText(R.id.widgetRowTitle, widgetRow.title)
            row.setTextViewText(R.id.widgetRowSubtitle, widgetRow.subtitle)
            row.setTextViewText(R.id.widgetRowTime, widgetRow.timeLabel)
            val badge = widgetRow.unread.coerceAtMost(99)
            row.setTextViewText(R.id.widgetRowBadge, if (badge > 0) "$badge" else "")
            row.setViewVisibility(R.id.widgetRowBadge, if (badge > 0) android.view.View.VISIBLE else android.view.View.GONE)

            // 行点击 → 打开会话（复用 MainActivity 的 EXTRA_OPEN_CHAT_ID 消费路径）
            val open = Intent(context, ConversationWidgetProvider::class.java)
                .setAction(ConversationWidgetContract.ACTION_OPEN_CHAT)
                .setData(Uri.parse(ConversationWidgetContract.rowDataUri("open", widgetId, rowIndex)))
                .putExtra(ConversationWidgetContract.EXTRA_WIDGET_ID, widgetId)
                .putExtra(ConversationWidgetContract.EXTRA_CHAT_ID, widgetRow.chatId)
                .putExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID, currentOwner(context))
            row.setOnClickPendingIntent(
                R.id.widgetRowRoot,
                PendingIntent.getBroadcast(
                    context,
                    ConversationWidgetContract.rowRequestCode(widgetId, rowIndex, 0),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // 快捷回复入口：AppWidget 的 RemoteViews 不支持 RemoteInput（平台限制，
            // setRemoteInputs 不存在于 RemoteViews API），点击「回复」改为打开对应会话，
            // 由用户在聊天页完成回复（Provider 的 ACTION_REPLY_SENT 处理保留备用）。
            val reply = Intent(context, ConversationWidgetProvider::class.java)
                .setAction(ConversationWidgetContract.ACTION_OPEN_CHAT)
                .setData(Uri.parse(ConversationWidgetContract.rowDataUri("reply", widgetId, rowIndex)))
                .putExtra(ConversationWidgetContract.EXTRA_WIDGET_ID, widgetId)
                .putExtra(ConversationWidgetContract.EXTRA_CHAT_ID, widgetRow.chatId)
                .putExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID, currentOwner(context))
            val replyPi = PendingIntent.getBroadcast(
                context,
                ConversationWidgetContract.rowRequestCode(widgetId, rowIndex, 1),
                reply,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            row.setOnClickPendingIntent(R.id.widgetRowReply, replyPi)

            // 标记已读
            val read = Intent(context, ConversationWidgetProvider::class.java)
                .setAction(ConversationWidgetContract.ACTION_MARK_READ)
                .setData(Uri.parse(ConversationWidgetContract.rowDataUri("read", widgetId, rowIndex)))
                .putExtra(ConversationWidgetContract.EXTRA_WIDGET_ID, widgetId)
                .putExtra(ConversationWidgetContract.EXTRA_CHAT_ID, widgetRow.chatId)
                .putExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID, currentOwner(context))
            row.setOnClickPendingIntent(
                R.id.widgetRowMarkRead,
                PendingIntent.getBroadcast(
                    context,
                    ConversationWidgetContract.rowRequestCode(widgetId, rowIndex, 2),
                    read,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            // 空态行（如未登录）：整行打开 App
            row.setTextViewText(R.id.widgetRowTitle, emptyLabel.orEmpty())
            row.setViewVisibility(R.id.widgetRowSubtitle, android.view.View.GONE)
            row.setViewVisibility(R.id.widgetRowTime, android.view.View.GONE)
            row.setViewVisibility(R.id.widgetRowReply, android.view.View.GONE)
            row.setViewVisibility(R.id.widgetRowMarkRead, android.view.View.GONE)
            val open = Intent(context, ConversationWidgetProvider::class.java)
                .setAction(ConversationWidgetContract.ACTION_OPEN_CHAT)
                .setData(Uri.parse(ConversationWidgetContract.rowDataUri("empty", widgetId, rowIndex)))
                .putExtra(ConversationWidgetContract.EXTRA_WIDGET_ID, widgetId)
            row.setOnClickPendingIntent(
                R.id.widgetRowRoot,
                PendingIntent.getBroadcast(
                    context,
                    ConversationWidgetContract.rowRequestCode(widgetId, rowIndex, 0),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        views.addView(R.id.widgetRowsContainer, row)
    }

    private fun chatTitle(chat: com.maodouchat.data.model.Chat, ownerUserId: String): String? {
        if (chat.isGroup) return chat.groupName?.takeIf { it.isNotBlank() }
        return chat.participants
            .firstOrNull { it.id != ownerUserId }
            ?.let { it.displayName.ifBlank { it.name } }
            ?: chat.groupName?.takeIf { it.isNotBlank() }
    }

    private fun previewOf(raw: String, type: MessageType, attachmentLabel: String): String {
        if (type != MessageType.TEXT && type != MessageType.MARKDOWN) return attachmentLabel
        val text = raw.substringBefore(Message.META_TAG_PREFIX).trim()
        return if (text.length <= 40) text else text.take(40) + "…"
    }

    private fun relativeTime(ts: Long): String {
        if (ts <= 0L) return ""
        return runCatching {
            DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }.getOrDefault("")
    }

    private fun currentOwner(context: Context): String =
        TokenManager.getInstance(context).getUserId().orEmpty()

    // ---- JSON 编解码 ----
    private fun encodeConfig(config: WidgetConfig): String =
        JSONObject()
            .put("chatIds", JSONArray(config.chatIds))
            .put("badge", config.showUnreadBadge)
            .put("compact", config.compact)
            .toString()

    private fun decodeConfig(json: String): WidgetConfig = runCatching {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("chatIds")
        val ids = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        WidgetConfig(
            chatIds = ids.take(ConversationWidgetContract.MAX_CONFIG_CHATS),
            showUnreadBadge = obj.optBoolean("badge", true),
            compact = obj.optBoolean("compact", false),
        )
    }.getOrDefault(WidgetConfig())

    private fun prefs(context: Context) =
        context.getSharedPreferences(ConversationWidgetContract.PREFS, Context.MODE_PRIVATE)

    private fun configKey(widgetId: Int): String = "config_$widgetId"

    private fun configuredKey(widgetId: Int): String = "configured_$widgetId"

    private const val KEY_LAST_PUSH_MS = "last_push_ms"
}

/** 应用作用域协程的轻量封装：失败静默，不中断其他任务 */
internal fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 后台刷新失败静默，下次同步重试
        }
    }
}
