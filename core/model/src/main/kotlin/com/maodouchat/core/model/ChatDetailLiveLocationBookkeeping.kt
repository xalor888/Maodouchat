package com.maodouchat.core.model

import kotlinx.coroutines.sync.Mutex

/** 实时位置记账：分享任务、取消句柄与最近一次位置载荷。从 ChatDetailDeps 抽出，纯搬移。 */
class ChatDetailLiveLocationBookkeeping {
    var liveLocationJob: kotlinx.coroutines.Job? = null
    var liveLocationCancel: (() -> Unit)? = null
    val liveLocationUpdateMutex = Mutex()
    @Volatile var lastLiveLocationPayload: LocationPayload? = null
}
