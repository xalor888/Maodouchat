package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message

// 时间线单条消息替换：按 id 把更新后的 Message 写回列表，其余项原样保留；
// 收敛聊天详情 AI 各簇与实时位置更新里 9 处逐字相同的行内 map。
object TimelineMessageReplacePolicy {

    fun replace(messages: List<Message>, messageId: String, updated: Message): List<Message> =
        messages.map { if (it.id == messageId) updated else it }
}
