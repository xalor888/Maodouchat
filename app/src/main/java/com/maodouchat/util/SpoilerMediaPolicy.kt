package com.maodouchat.util

import com.maodouchat.data.model.MessageType

/**
 * 剧透媒体（Telegram 式「模糊直到点击」）的类型策略（G154b）。
 *
 * 收敛前，`AttachmentIntentController` 与 `AttachmentSendWorkflow`
 * **各有一处内联**的 `msgType in setOf(IMAGE, VIDEO, GIF)`——同一个判断写了三遍
 * （另一处是 `MessagePresentation.VIEW_ONCE_TYPES`）。
 *
 * 注意：这个集合当前与 [ViewOncePolicy] 的 `SUPPORTED` **取值相同但概念独立**——
 * 「能设剧透」和「能阅后即焚」是两个产品决策，将来新增类型很可能只支持其一
 * （例如贴纸可以剧透但不该阅后即焚）。所以刻意不合并这两个常量。
 */
object SpoilerMediaPolicy {
    val SUPPORTED = setOf(
        MessageType.IMAGE,
        MessageType.VIDEO,
        MessageType.GIF
    )

    /** true 表示该消息类型可以在发送前开启剧透模糊。 */
    fun supports(type: MessageType): Boolean = type in SUPPORTED
}
