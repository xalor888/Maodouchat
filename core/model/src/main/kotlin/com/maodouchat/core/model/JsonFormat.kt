package com.maodouchat.core.model

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageMetaJson

// 编码侧（encode / composeContentWithMeta 等）已迁入 :domain:messaging 的 MessageJsonPolicy；
// 此处仅留 fromJsonString（ContentPayloadCodec 锚定）。
object JsonFormat {
    /** 从一段 JSON 字符串解析出 MessageMeta */
    fun fromJsonString(text: String): MessageMeta = MessageMetaJson.fromJsonString(text)
}
