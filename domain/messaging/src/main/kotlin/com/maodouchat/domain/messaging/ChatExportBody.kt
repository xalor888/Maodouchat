package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType

/** 聊天导出纯文本簇：消息正文渲染与导出文件名净化（原 app 侧 ChatExport 内联逻辑，逐字平移）。 */
object ChatExportBody {

    fun renderBody(message: Message): String = when (message.type) {
        MessageType.TEXT, MessageType.MARKDOWN, MessageType.GIF -> message.parsedContent()
        MessageType.IMAGE -> "[图片]"
        MessageType.STICKER -> "[表情]"
        MessageType.LOCATION -> "[位置]"
        MessageType.VOICE -> "[语音]"
        MessageType.VIDEO -> "[视频]"
        MessageType.FILE -> message.meta.fileName?.let { "[文件] $it" } ?: "[文件]"
        MessageType.NUDGE -> "[戳一戳]"
        MessageType.REVOKED -> "[消息已撤回]"
        MessageType.SYSTEM, MessageType.SK_DIST -> ""
    }

    private val unsafeCharsRegex = Regex("[\\\\/:*?\"<>|]")

    fun sanitizeExportFileName(fileName: String): String =
        fileName.replace(unsafeCharsRegex, "_").take(80)
}
