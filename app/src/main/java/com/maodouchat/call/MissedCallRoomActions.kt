package com.maodouchat.call

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.MissedCallRepository

/**
 * 通话记录页对 Room 未接来电记录的写操作（U02 延伸：自 `ui/screen/call/CallHistoryScreen`
 * 的 app 单例/Room 直连收口）。
 *
 * 原实现两处（「清空」与行内「删除」）都是：
 * `(context.applicationContext as? MaodouchatApp)?.let { scope.launch { MissedCallRepository(it.database.missedCallDao()).clearAll()/delete(id) } }`
 * ——ui 直连持久层棘轮各记 2 处。收进这里后语义不变：
 * - app 未初始化（非生产场景）→ 静默 no-op（与原 `as?` 空判一致）；
 * - 仓库动作抛错 → 照旧向调用方（`scope.launch`）传播，不吞。
 *
 * 与 `CallLogStore`（本机通话日志）的分工不变：清空/删除要**同时**动两者，
 * 本类只负责 Room 那半，调用方保持既有顺序。
 */
object MissedCallRoomActions {

    /**
     * 测试缝（JVM 单测注入假仓库；生产默认从 app 单例取 Room DAO）。
     * 返回 null 表示「当前环境没有可用的 app 实例」→ 调用方 no-op。
     */
    internal var repositoryFactory: () -> MissedCallRepository? = {
        runCatching {
            MissedCallRepository(MaodouchatApp.instance.database.missedCallDao())
        }.getOrNull()
    }

    suspend fun clearAll() {
        repositoryFactory.invoke()?.clearAll()
    }

    suspend fun delete(entryId: String) {
        repositoryFactory.invoke()?.delete(entryId)
    }
}
