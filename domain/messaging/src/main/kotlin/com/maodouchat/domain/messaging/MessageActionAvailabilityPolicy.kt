package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType

// 长按消息操作菜单的可用性判定：编辑/撤回/举报三条谓词由 app 侧两处
// （旧弹窗 ChatDetailMessageActionsDialog 与底部弹层 ChatDetailMessageManageActions）
// 逐字重复的内联条件收敛到此处；窗口算术仍由 MessageEditWindowPolicy 提供。
object MessageActionAvailabilityPolicy {

    // 仅本人、5 分钟窗口内、文本/Markdown 可编辑。
    fun canEdit(isOwn: Boolean, withinEditWindow: Boolean, type: MessageType): Boolean =
        isOwn && withinEditWindow && (type == MessageType.TEXT || type == MessageType.MARKDOWN)

    // 仅本人、5 分钟窗口内、未被撤回的消息可撤回。
    fun canRevoke(isOwn: Boolean, withinEditWindow: Boolean, type: MessageType): Boolean =
        isOwn && withinEditWindow && type != MessageType.REVOKED

    // 仅他人的非系统类消息可举报（密钥分发/系统/已撤回除外）。
    fun canReport(isOwn: Boolean, type: MessageType): Boolean =
        !isOwn && type !in setOf(MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED)
}
