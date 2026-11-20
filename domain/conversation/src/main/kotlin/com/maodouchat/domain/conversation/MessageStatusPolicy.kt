package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus

// 送达阶梯的推进判定：唯一生产调用方是 ChatTimelineStateController；
// 阶梯 rank（deliveryRank）留守 core 的 MessageStatus。
/** True when [next] is a valid advance (or same) on the delivery ladder. FAILED only replaces SENDING. */
fun MessageStatus.canAdvanceTo(next: MessageStatus): Boolean {
    if (this == next) return true
    if (next == MessageStatus.FAILED) return this == MessageStatus.SENDING
    // 8.45：FAILED 允许被服务端权威帧（SENT/DELIVERED/READ）覆盖——失败只是本地
    // side-channel；若消息实际已送达（服务端状态流可能跳过 SENT 直接发 DELIVERED），
    // 应恢复为真实送达状态而非永远显示失败。
    if (this == MessageStatus.FAILED) return next == MessageStatus.SENDING || next == MessageStatus.SENT || next == MessageStatus.DELIVERED || next == MessageStatus.READ
    return next.deliveryRank() > deliveryRank()
}
