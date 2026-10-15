package com.maodouchat.ui.screen.chatdetail

internal data class HistoryLoadPlan(
    /** 「以下为未读消息」分隔线落在哪条；null 表示不画。 */
    val unreadSeparatorId: String?,
    /** 是否武装阅后即焚（密聊）。 */
    val armSecretDisappearing: Boolean,
    /** 是否入队已读回执。 */
    val enqueueReadReceipt: Boolean,
    /** 回执的「已读到这条为止」。 */
    val readReceiptThroughMessageId: String?,
    /** 回执带的群 revision；直聊或缺失时为 null。 */
    val readReceiptGroupRevision: Long?,
)
