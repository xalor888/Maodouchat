package com.maodouchat.realtime

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 管理端公告（第三方服务器运营公告）的 UI 可见流（U02 延伸：自 `NavGraph` 迁出的
 * app 单例访问收口）。
 *
 * 语义（逐字对齐原收集器）：
 * - **收集开始时**捕获 owner（`CurrentSession.ownerUserId()`）——登出/换号竞态下，
 *   旧账号收到的公告不得弹给新账号；
 * - 每条事件再过实时 `BackgroundSessionGate`（会话已失效则丢弃）；
 * - 正文 trim 后为空的事件丢弃。
 *
 * 放在非 ui 层的原因同其余收口：ui 只收集并落状态，不再触碰 `MaodouchatApp`。
 */
object AdminNoticeFeed {

    fun visibleNotices(): Flow<RealtimeDomainEvent.AdminNotice> = flow {
        val ownerUserId = CurrentSession.ownerUserId()
        MaodouchatApp.instance.realtimeEventDispatcher.adminNoticeEvents.collect { event ->
            if (ownerUserId.isBlank() || !BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)) {
                return@collect
            }
            if (event.text.trim().isBlank()) return@collect
            emit(event)
        }
    }
}
