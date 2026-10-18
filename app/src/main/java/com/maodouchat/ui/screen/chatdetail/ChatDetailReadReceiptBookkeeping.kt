package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.MessageStatus

/** 已读回执记账：seen 集合、待写水印与 mark-read 任务。从 ChatDetailDeps 抽出，纯搬移。 */
internal class ChatDetailReadReceiptBookkeeping {
    internal val readSeenMessages = SeenSet()
    internal var pendingReadWatermarkMessageId: String? = null
    internal var lastMessagesSeen: List<Pair<String, MessageStatus>>? = null
    internal var markReadJob: kotlinx.coroutines.Job? = null
}
