package com.maodouchat.domain.conversation

// 全局搜索结果行图标种类；图标与 tint 的 Compose 映射仍在 app 侧。
enum class GlobalSearchHitIconKind {
    IMAGE,
    VIDEO,
    FILE,
    VOICE,
    LOCATION,
    AI_SEMANTIC,
    TEXT,
}

fun globalSearchHitIconKind(messageType: String, semanticScore: Double?): GlobalSearchHitIconKind =
    when (messageType) {
        "IMAGE", "GIF", "STICKER" -> GlobalSearchHitIconKind.IMAGE
        "VIDEO" -> GlobalSearchHitIconKind.VIDEO
        "FILE" -> GlobalSearchHitIconKind.FILE
        "VOICE" -> GlobalSearchHitIconKind.VOICE
        "LOCATION" -> GlobalSearchHitIconKind.LOCATION
        else -> if (semanticScore != null) GlobalSearchHitIconKind.AI_SEMANTIC else GlobalSearchHitIconKind.TEXT
    }
