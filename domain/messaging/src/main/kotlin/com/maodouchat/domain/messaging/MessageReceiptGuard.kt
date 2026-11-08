package com.maodouchat.domain.messaging

/**
 * 回执投影的准入判定。纯逻辑：只看发送方身份，落库与状态推进仍在调用方。
 */
object MessageReceiptGuard {
    /**
     * 送达回执只对自己发出的消息、且由对方设备回执时才落库：
     * 自己给自己的回执、别人消息的回执都不推进。
     */
    fun shouldApplyDeliveryReceipt(
        messageSenderId: String,
        ownerUserId: String,
        receiptSenderUserId: String,
    ): Boolean = messageSenderId == ownerUserId && receiptSenderUserId != ownerUserId

    /** 对方发来的已读回执里，发送方是自己表示这是本端已读的回声。 */
    fun isOwnReadReceipt(receiptSenderUserId: String, ownerUserId: String): Boolean =
        receiptSenderUserId == ownerUserId

    /** 对端已读回执只推进自己发出的消息。 */
    fun shouldApplyPeerReadReceipt(boundarySenderId: String, ownerUserId: String): Boolean =
        boundarySenderId == ownerUserId

    /** 播放回执只对自己发出的消息、且由对方回执时才落库。 */
    fun shouldApplyPlayReceipt(
        targetSenderId: String,
        ownerUserId: String,
        receiptSenderUserId: String,
    ): Boolean = targetSenderId == ownerUserId && receiptSenderUserId != ownerUserId
}
