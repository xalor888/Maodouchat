package com.maodouchat.widget

import android.app.IntentService
import android.content.Intent
import android.os.Process

/**
 * B5 主屏小组件 · 同步服务（manifest 声明见 AndroidManifest.xml 追加段）。
 *
 * 仅在应用进程存活且会话合法时更新快照，避免绕过会话门禁读取密聊/锁定会话。
 * 周期由 AlarmManager 驱动的 ACTION_SYNC_TICK 广播触发（见 ConversationWidgetData.startSync）。
 */
class ConversationWidgetSyncService : IntentService("ConversationWidgetSyncService") {

    override fun onHandleIntent(intent: Intent?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        ConversationWidgetData.refreshAll(applicationContext)
    }
}
