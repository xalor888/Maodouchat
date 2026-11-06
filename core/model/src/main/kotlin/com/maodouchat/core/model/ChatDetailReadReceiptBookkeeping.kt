package com.maodouchat.core.model

/** 已读回执记账：seen 集合、待写水印与 mark-read 任务。从 ChatDetailDeps 抽出，纯搬移。 */
class ChatDetailReadReceiptBookkeeping {
    val readSeenMessages = SeenSet()
    var pendingReadWatermarkMessageId: String? = null
    var lastMessagesSeen: List<Pair<String, MessageStatus>>? = null
    var markReadJob: kotlinx.coroutines.Job? = null
}
