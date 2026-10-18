package com.maodouchat.ui.screen.chatdetail

import kotlinx.coroutines.sync.Mutex

/** 实时位置记账：分享任务、取消句柄与最近一次位置载荷。从 ChatDetailDeps 抽出，纯搬移。 */
internal class ChatDetailLiveLocationBookkeeping {
    internal var liveLocationJob: kotlinx.coroutines.Job? = null
    internal var liveLocationCancel: (() -> Unit)? = null
    internal val liveLocationUpdateMutex = Mutex()
    @Volatile internal var lastLiveLocationPayload: com.maodouchat.data.model.LocationPayload? = null
}
