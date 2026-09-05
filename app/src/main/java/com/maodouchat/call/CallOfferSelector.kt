package com.maodouchat.call

import com.maodouchat.webrtc.WebRTCSignaling.SignalMessage

/**
 * 来电 offer 选择纯策略（P03）。
 *
 * 自 `IncomingCallObserver` 的 REST 轮询与 WS 两条路径抽出的共享谓词：
 * 终端信令判定、群 mesh 边过滤、首选 offer 选择。两条路径的 pending 去重
 * 谓词有意不同（WS 处理实时单事件、轮询处理批量），保留在调用方。
 */
object CallOfferSelector {

    /** 终端信令：挂断/忙/拒（大小写不敏感）。 */
    fun isTerminalType(type: String): Boolean {
        val t = type.lowercase()
        return t == "hang-up" || t == "busy" || t == "reject"
    }

    /** 批量消息中的已终止 callId（非空）。 */
    fun terminatedCallIds(messages: List<SignalMessage>): Set<String> =
        messages.filter { isTerminalType(it.type) }
            .map { it.callId }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * 8.56：直聊响铃 offer 判定——群 mesh 边 offer（groupInvite=false 且带 groupId）
     * 不得走来电路由，否则轮询/WS 会把群内边 offer 当新来电 RINGING。
     */
    fun isDirectRingOffer(groupId: String, groupInvite: Boolean): Boolean =
        groupId.isBlank() || groupInvite

    /**
     * 首选 offer：FCM 指定的 preferCallId 命中则取之，否则取首个；
     * 返回首选与剩余（调用方依次派发剩余）。
     */
    fun selectPrimary(
        offers: List<SignalMessage>,
        preferCallId: String,
    ): Pair<SignalMessage, List<SignalMessage>> {
        val primary = offers.firstOrNull { preferCallId.isNotBlank() && it.callId == preferCallId }
            ?: offers.first()
        return primary to offers.filterNot { it === primary }
    }
}
