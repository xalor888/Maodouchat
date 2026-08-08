package com.maodouchat.widget

/**
 * B5 主屏小组件 · 组件间契约（intent 协议 / extras / 常量）。
 *
 * 所有 PendingIntent 一律 FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT，并按
 * (widgetId, rowIndex) 派生 requestCode + data URI，保证跨会话不串目标。
 */
object ConversationWidgetContract {

    // ---- 广播 action ----
    const val ACTION_SYNC_TICK = "com.maodouchat.widget.SYNC_TICK"           // AlarmManager 周期同步
    const val ACTION_REPLY_SENT = "com.maodouchat.widget.REPLY_SENT"         // 快捷回复（含 RemoteInput 结果）
    const val ACTION_MARK_READ = "com.maodouchat.widget.MARK_READ"           // 标记已读
    const val ACTION_OPEN_CHAT = "com.maodouchat.widget.OPEN_CHAT"           // 打开会话（行点击）
    const val ACTION_TOGGLE_BALL = "com.maodouchat.widget.TOGGLE_BALL"       // 切换悬浮球（设置项 action，不用于系统广播）

    // ---- RemoteInput 键 ----
    const val EXTRA_REPLY_TEXT = "maodouchat_widget_reply_text"

    // ---- extras ----
    const val EXTRA_WIDGET_ID = "maodouchat_widget_id"
    const val EXTRA_CHAT_ID = "maodouchat_widget_chat_id"
    const val EXTRA_OWNER_USER_ID = "maodouchat_widget_owner_user_id"

    // ---- 上限 ----
    const val MAX_ROWS = 4          // 标准布局最多显示行数
    const val MAX_ROWS_COMPACT = 3  // 紧凑布局最多显示行数
    const val MAX_CONFIG_CHATS = 6  // 配置中最多钉住的会话数
    const val MAX_REPLY_LENGTH = 2000

    // ---- 持久化 ----
    const val PREFS = "conversation_widget"
    const val KEY_ENABLED = "enabled"
    const val KEY_SYNC_PERIOD_MIN = 30L
    const val PUSH_WINDOW_MS = 2500L   // 去抖窗口：同进程内重复请求合并

    /** 行操作 PendingIntent 的唯一 requestCode（widgetId 前缀 + 行号） */
    fun rowRequestCode(widgetId: Int, rowIndex: Int, salt: Int): Int =
        widgetId * 1000 + rowIndex * 10 + salt

    /** 行操作 data URI，保证不同 (widgetId, row) 的 PendingIntent 身份互异 */
    fun rowDataUri(kind: String, widgetId: Int, rowIndex: Int): String =
        "maodouchat-widget://$kind/$widgetId/$rowIndex"
}
