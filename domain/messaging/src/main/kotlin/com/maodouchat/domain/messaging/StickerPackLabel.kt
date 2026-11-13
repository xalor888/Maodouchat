package com.maodouchat.domain.messaging

// 贴纸包展示名：稳定 key → 文案种类；R.string 映射留在 app 侧 @Composable 壳里。
enum class StickerPackLabel {
    Mood, Gesture, Party, RawKey
}

// 原 stickerPackLabel 的 when 分支逐字平移；未列出的 key 原样回显。
fun stickerPackLabelKind(nameKey: String): StickerPackLabel = when (nameKey) {
    "mood" -> StickerPackLabel.Mood
    "gesture" -> StickerPackLabel.Gesture
    "party" -> StickerPackLabel.Party
    else -> StickerPackLabel.RawKey
}
