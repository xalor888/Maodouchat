package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatRealtimeText

/** domain 侧 ChatRealtimeText 到 app 字符串资源的映射。 */
internal fun chatRealtimeTextResId(key: ChatRealtimeText): Int = when (key) {
    ChatRealtimeText.CONNECTION_FAILED -> R.string.chat_ws_connection_failed
    ChatRealtimeText.DATA_INVALID -> R.string.chat_ws_data_invalid
}
