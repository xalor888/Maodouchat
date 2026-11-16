package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Flag

// 附件种类→发送功能开关：开关 key/default 与 app 侧 RuntimeFlags 同源，
// isEnabled 按 key 查 SharedPreferences，domain 侧构造等值 Flag 行为一致。
object AttachmentSendFlagPolicy {

    /** 返回该种类发送前必须开启的开关与禁用文案；无开关要求时返回 null。 */
    fun requiredFlag(kind: AttachmentKind): Pair<Flag, String>? =
        when (kind) {
            AttachmentKind.IMAGE -> Flag("image_send_enabled", true) to "image_send_disabled"
            AttachmentKind.VIDEO -> Flag("video_send_enabled", true) to "video_send_disabled"
            AttachmentKind.GIF -> Flag("gif_send_enabled", true) to "gif_send_disabled"
            AttachmentKind.FILE -> Flag("file_share_enabled", true) to "file_share_disabled"
            else -> null
        }
}
