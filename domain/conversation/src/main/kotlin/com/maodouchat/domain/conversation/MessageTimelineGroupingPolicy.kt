package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message

// 时间线相邻消息分组判定：同一发送者 6 分钟内的连续消息紧凑成组。
// 与 :core:model 的 sameSenderGroup 同源同窗口，但不复核日期标签——调用点上
// 相邻两条 Msg 之间若跨天必有 DateSeparator 隔开，日期相等由列表结构保证。
object MessageTimelineGroupingPolicy {

    fun isGroupedWithPrevious(message: Message, previous: Message, windowMs: Long = SENDER_GROUP_WINDOW_MS): Boolean =
        previous.senderId == message.senderId &&
            kotlin.math.abs(message.timestamp - previous.timestamp) < windowMs
}
