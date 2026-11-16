package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatItem

// 时间线 contentType：LazyColumn 按条目类型复用组合，字符串与原 contentType lambda 逐字一致。
fun ChatItem.timelineContentType(): String =
    when (this) {
        is ChatItem.DateSeparator -> "date_separator"
        is ChatItem.Msg -> "message_${message.type.name}"
        is ChatItem.UnreadSeparator -> "unread_separator"
    }
