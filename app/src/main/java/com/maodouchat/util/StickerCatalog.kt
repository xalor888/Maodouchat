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

object StickerCatalog {
    const val PACK_RECENT = "recent"
    const val PACK_MOOD = "mood"
    const val PACK_GESTURE = "gesture"
    const val PACK_PARTY = "party"

    val BUILT_IN_PACKS: List<StickerPack> = listOf(
        StickerPack(
            id = PACK_MOOD,
            nameKey = "mood",
            stickers = listOf(
                "🥳", "🤗", "🫡", "🤩", "🥹", "😶‍🌫️",
                "🤣", "😴", "🤔", "😤", "😭", "🤯",
                "😊", "😍", "😎", "😱", "🥺", "😇",
                "🥲", "😬", "🫠", "😌", "😜", "😈",
                "🥰", "😏", "🤒", "🥶"
            ),
            emojiTags = mapOf(
                "happy" to listOf("🥳", "🤩", "😊", "😍", "😜", "😏"),
                "开心" to listOf("🥳", "🤩", "😊", "😍", "😜", "😏"),
                "sad" to listOf("😭", "🥺", "🥹", "🥲"),
                "难过" to listOf("😭", "🥺", "🥹", "🥲"),
                "think" to listOf("🤔", "😶‍🌫️", "😬"),
                "思考" to listOf("🤔", "😶‍🌫️", "😬"),
                "angry" to listOf("😤", "🤯", "😈"),
                "生气" to listOf("😤", "🤯", "😈"),
                "sleep" to listOf("😴", "😌"),
                "睡觉" to listOf("😴", "😌"),
                "love" to listOf("😍", "🥰"),
                "爱" to listOf("😍", "🥰"),
                "sick" to listOf("🤒", "🥶"),
                "生病" to listOf("🤒", "🥶"),
                "cold" to listOf("🥶"),
                "冷" to listOf("🥶"),
            )
        ),
        StickerPack(
            id = PACK_GESTURE,
            nameKey = "gesture",
            stickers = listOf(
                "👍", "👎", "👏", "🙏", "💪", "🤝",
                "✌️", "🤞", "👌", "🤟", "👋", "🤙",
                "👀", "🧠", "💬", "🤫", "🫡", "🙌",
                "🫶", "👊", "✋", "☝️", "👉", "👈",
                "🤘", "🫰", "🫵", "🖖"
            ),
            emojiTags = mapOf(
                "ok" to listOf("👍", "👌", "✌️"),
                "好" to listOf("👍", "👌", "✌️"),
                "no" to listOf("👎"),
                "不行" to listOf("👎"),
                "hi" to listOf("👋", "🤙"),
                "你好" to listOf("👋", "🤙"),
                "pray" to listOf("🙏"),
                "谢谢" to listOf("🙏"),
                "strong" to listOf("💪", "👊"),
                "加油" to listOf("💪", "👊"),
                "point" to listOf("👉", "👈", "☝️", "🫵"),
                "指向" to listOf("👉", "👈", "☝️", "🫵"),
                "rock" to listOf("🤘", "🤟"),
                "摇滚" to listOf("🤘", "🤟"),
                "vulcan" to listOf("🖖"),
                "致敬" to listOf("🖖", "🫡"),
                "heart hands" to listOf("🫶"),
                "比心" to listOf("🫶"),
            )
        ),
        StickerPack(
            id = PACK_PARTY,
            nameKey = "party",
            stickers = listOf(
                "🎉", "🎊", "✨", "🔥", "💯", "❤️",
                "💔", "⭐", "🌟", "🎈", "🎁", "🍾",
                "🐱", "🐶", "🐼", "🦊", "🐰", "🐻",
                "🦄", "🌈", "🍭", "🎂", "🎵", "🚀",
                "🍀", "🍕", "☕", "🏆"
            ),
            emojiTags = mapOf(
                "party" to listOf("🎉", "🎊", "🎈", "🍾", "🎂"),
                "派对" to listOf("🎉", "🎊", "🎈", "🍾", "🎂"),
                "fire" to listOf("🔥", "💯", "🚀"),
                "燃" to listOf("🔥", "💯", "🚀"),
                "love" to listOf("❤️", "💔"),
                "心" to listOf("❤️", "💔"),
                "star" to listOf("⭐", "🌟", "✨"),
                "星星" to listOf("⭐", "🌟", "✨"),
                "pet" to listOf("🐱", "🐶", "🐼", "🦊", "🐰", "🐻", "🦄"),
                "动物" to listOf("🐱", "🐶", "🐼", "🦊", "🐰", "🐻", "🦄"),
                "music" to listOf("🎵"),
                "音乐" to listOf("🎵"),
                "food" to listOf("🍭", "🎂", "🍕", "☕"),
                "食物" to listOf("🍭", "🎂", "🍕", "☕"),
                "coffee" to listOf("☕"),
                "咖啡" to listOf("☕"),
                "luck" to listOf("🍀", "🏆"),
                "好运" to listOf("🍀", "🏆"),
                "win" to listOf("🏆"),
                "冠军" to listOf("🏆"),
            )
        ),
    )

    fun packById(id: String): StickerPack? = BUILT_IN_PACKS.firstOrNull { it.id == id }

    fun allStickers(): List<String> = BUILT_IN_PACKS.flatMap { it.stickers }.distinct()

    fun defaultEnabledPackIds(): List<String> = BUILT_IN_PACKS.map { it.id }
}
