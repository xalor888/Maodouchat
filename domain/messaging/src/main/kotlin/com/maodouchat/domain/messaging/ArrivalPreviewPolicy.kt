package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType

/** 新消息到达通知的预览内容。纯模型：字符串资源仍由调用方按种类取，这里只定“显示什么”。 */
sealed interface ArrivalPreview {
    /** 文本类消息：截断到 500 字的正文。 */
    data class Text(val text: String) : ArrivalPreview

    /** 图片 / 动图 / 贴纸 / 位置 / 语音 / 视频 / 文件 / 戳一戳：调用方取对应字符串。 */
    data object Image : ArrivalPreview
    data object Gif : ArrivalPreview
    data object Sticker : ArrivalPreview
    data object Location : ArrivalPreview
    data object Voice : ArrivalPreview
    data object Video : ArrivalPreview
    data object File : ArrivalPreview
    data object Nudge : ArrivalPreview

    /** 撤回与 Sender Key 分发：统一显示为加密消息。 */
    data object Encrypted : ArrivalPreview
}

/** 到达通知预览判定。纯逻辑：只看消息类型，正文取 parsedContent 后截断 500 字。 */
object ArrivalPreviewPolicy {
    fun preview(message: Message): ArrivalPreview = when (message.type) {
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.SYSTEM -> ArrivalPreview.Text(message.parsedContent().take(500))
        MessageType.IMAGE -> ArrivalPreview.Image
        MessageType.GIF -> ArrivalPreview.Gif
        MessageType.STICKER -> ArrivalPreview.Sticker
        MessageType.LOCATION -> ArrivalPreview.Location
        MessageType.VOICE -> ArrivalPreview.Voice
        MessageType.VIDEO -> ArrivalPreview.Video
        MessageType.FILE -> ArrivalPreview.File
        MessageType.NUDGE -> ArrivalPreview.Nudge
        MessageType.REVOKED,
        MessageType.SK_DIST -> ArrivalPreview.Encrypted
    }
}
