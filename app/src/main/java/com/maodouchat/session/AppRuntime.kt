package com.maodouchat.session

import android.app.Application
import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * 进程级 app 设施的非 ui 访问点（U02 延伸：自 `ui/screen/call/CallViewModel` 的
 * `MaodouchatApp` 直连收口）。
 *
 * 与 `CurrentSession` 的分工：那边回答「当前是谁/令牌是什么」；本对象回答
 * 「进程级设施在哪」——会话世代（丢弃过期事件用）、应用级协程域、实时事件分发器。
 * 三者都是 ui 层被允许**读取**但不该直接依赖 app 单例符号的东西；收到这里后，
 * ui 直连持久层棘轮对应的命中归零，且 JVM 单测/真机测试都可从一处注入或断言。
 *
 * 注意：[realtimeDispatcherOrNull] 保留「非 MaodouchatApp 实例 → null」的原语义
 * （调用点允许它为空并跳过订阅），不要改成抛错。
 */
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
