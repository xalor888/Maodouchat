package com.maodouchat.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maodouchat.R
import com.maodouchat.network.TokenManager

/**
 * B5 主屏小组件 · 实例配置页（manifest 声明见 AndroidManifest.xml 追加段）。
 *
 * 功能：
 * - 从会话列表勾选至多 MAX_CONFIG_CHATS 个会话；
 * - 顶部未读总数角标开关；
 * - 紧凑布局开关（至多 3 行，无头部）；
 * - 保存后立即按配置渲染该实例并启动周期同步。
 */
class ConversationWidgetConfigActivity : ComponentActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val tokenManager = TokenManager.getInstance(this)
        if (tokenManager.getUserId().isNullOrBlank() || tokenManager.getToken().isNullOrBlank()) {
            Toast.makeText(this, R.string.widget_config_requires_login, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val initialConfig = ConversationWidgetData.widgetConfig(this, widgetId)
        setContent {
            ConversationWidgetConfigContent(
                initial = initialConfig,
                onSave = { chatIds, badge, compact -> saveAndFinish(chatIds, badge, compact) },
            )
        }
    }

    private fun saveAndFinish(chatIds: List<String>, badge: Boolean, compact: Boolean) {
        ConversationWidgetData.saveConfig(
            this,
            widgetId,
            ConversationWidgetData.WidgetConfig(
                chatIds = chatIds.take(ConversationWidgetContract.MAX_CONFIG_CHATS),
                showUnreadBadge = badge,
                compact = compact,
            )
        )
        ConversationWidgetData.refresh(this, listOf(widgetId))
        ConversationWidgetData.startSync(this)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }
}
