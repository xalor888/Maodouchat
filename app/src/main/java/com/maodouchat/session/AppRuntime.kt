package com.maodouchat.session

import android.app.Application
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import kotlinx.coroutines.CoroutineScope

object AppRuntime {

    /** 会话世代：登出/换号会自增，用于丢弃跨会话的过期事件。 */
    val currentSessionGeneration: Long
        get() = MaodouchatApp.currentSessionGeneration()

    /** 应用级协程域（与进程同生命周期；后台 fire-and-forget 用）。 */
    val applicationScope: CoroutineScope
        get() = MaodouchatApp.instance.applicationScope

    /** 实时事件分发器；非本应用实例（测试替身等）返回 null。 */
    fun realtimeDispatcherOrNull(application: Application): RealtimeEventDispatcher? =
        (application as? MaodouchatApp)?.realtimeEventDispatcher

    /**
     * 尝试拉起实时连接（已登录时）；调用方传 applicationContext。
     * 非 MaodouchatApp 实例（测试替身等）→ 静默 no-op，与原 ui 内联的 `as?` 空判一致。
     */
    fun ensureRealtimeConnected(appContext: Context) {
        (appContext as? MaodouchatApp)?.ensureRealtimeConnected()
    }
}
