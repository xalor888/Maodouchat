package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessagePinPolicy
import com.maodouchat.core.model.PreviewKind

// 置顶横幅预览：消息 → 预览种类；R.string 映射留在 app 侧 @Composable 壳里。
sealed interface PinnedPreview {
    data class Text(val text: String) : PinnedPreview
    data object Image : PinnedPreview
    data object Voice : PinnedPreview
    data object Video : PinnedPreview
    data object File : PinnedPreview
    data object Location : PinnedPreview
    data object Sticker : PinnedPreview
    data object Generic : PinnedPreview
}

// 原 pinnedPreviewText 的判定逐字平移；空消息与未知类型落通用预览。
fun pinnedPreview(message: Message?): PinnedPreview {
    if (message == null) return PinnedPreview.Generic
    return when (MessagePinPolicy.previewKind(message.type)) {
        PreviewKind.TEXT -> PinnedPreview.Text(MessagePinPolicy.textPreview(message.content))
        PreviewKind.IMAGE -> PinnedPreview.Image
        PreviewKind.VOICE -> PinnedPreview.Voice
        PreviewKind.VIDEO -> PinnedPreview.Video
        PreviewKind.FILE -> PinnedPreview.File
        PreviewKind.LOCATION -> PinnedPreview.Location
        PreviewKind.STICKER -> PinnedPreview.Sticker
        PreviewKind.GENERIC -> PinnedPreview.Generic
    }
}
