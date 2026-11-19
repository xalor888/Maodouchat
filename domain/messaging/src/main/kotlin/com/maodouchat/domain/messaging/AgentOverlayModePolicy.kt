package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentOverlayMode

// 悬浮球模式的存储值解析，未知/缺失值回落 DISABLED，自 app 侧 ai/agent 存储簇收敛。
object AgentOverlayModePolicy {

    fun parse(raw: String?): AgentOverlayMode =
        runCatching { AgentOverlayMode.valueOf(raw.orEmpty()) }.getOrDefault(AgentOverlayMode.DISABLED)
}
