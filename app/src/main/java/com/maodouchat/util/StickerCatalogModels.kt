package com.maodouchat.util

/**
 * 内置贴纸包目录（本地资源，不依赖服务端）。
 * 贴纸内容仍走既有 STICKER 消息（emoji 字符），包只负责浏览与最近使用组织。
 */
data class StickerPack(
    val id: String,
    /** 展示名 string resource 由 UI 解析；此处存稳定 key */
    val nameKey: String,
    val stickers: List<String>,
    /** 关联搜索：emoji / 关键词（小写）→ 贴纸 */
    val emojiTags: Map<String, List<String>> = emptyMap(),
    val builtIn: Boolean = true,
)
