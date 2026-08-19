package com.maodouchat.widget

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/**
 * B5 主屏小组件 · 同步服务（manifest 声明见 AndroidManifest.xml 追加段）。
 *
 * 仅在应用进程存活且会话合法时更新快照，避免绕过会话门禁读取密聊/锁定会话。
 * 周期由 AlarmManager 驱动的 ACTION_SYNC_TICK 广播触发（见 ConversationWidgetData.startSync）。
 *
 * 不使用已弃用的 IntentService：refreshAll 内部在 applicationScope 协程中执行，
 * 本服务只需触发后立即停止。
 */
class ConversationWidgetSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        ConversationWidgetData.refreshAll(applicationContext)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
