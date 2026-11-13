package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 可解密的消息类型：内容型 9 类可解密，控制型不可解密。自 app 侧 ChatDetailViewModel.kt 逐字平移。
fun MessageType.isDecryptable(): Boolean = this in setOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
    MessageType.VOICE, MessageType.FILE,
)
