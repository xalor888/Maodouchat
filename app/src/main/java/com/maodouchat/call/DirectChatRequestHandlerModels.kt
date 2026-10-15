package com.maodouchat.call

sealed interface Outcome {
    /** 无有效会话（token/owner 缺失或门禁不过）。 */
    data object SessionExpired : Outcome

    /** 请求在途期间会话失效：静默丢弃。 */
    data object Dropped : Outcome

    /** 成功建/取到私聊，调用方导航到会话。 */
    data class OpenChat(val chatId: String) : Outcome

    /** HTTP/其他失败；message 可能为空。 */
    data class Failed(val message: String?) : Outcome
}
