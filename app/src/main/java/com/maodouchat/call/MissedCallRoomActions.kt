package com.maodouchat.call

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.MissedCallRepository

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
