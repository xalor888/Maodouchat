package com.maodouchat.ui.screen.chatdetail

// 离线建议话术库：纯关键词匹配，无 VM / 网络依赖。簇已按主题拆出同包三文件。
internal object OfflineSuggestionPhrases {

    fun suggest(lower: String, seed: String, tone: String): List<String> {
        val base = OfflineGreetingPhrases.match(lower, seed)
            ?: OfflineLifePhrases.match(lower, seed)
            ?: OfflinePlayPhrases.match(lower, seed)
            ?: listOf(
                "收到，我晚点仔细回你。",
                "明白了。",
                "嗯嗯，继续说。"
            )
        val toned = when (tone) {
            "formal" -> base.map {
                it.replace("～", "。").replace("嗯嗯，", "好的，")
            }
            "concise" -> base.map { it.take(14) }
            "humorous" -> base.map { "$it :)" }
            "warm" -> base.map { if (it.endsWith("。") || it.endsWith("～")) it else "$it～" }
            else -> base
        }
        return toned.distinct().take(4)
    }
}

// 共享关键词匹配：lower 做大小写不敏感包含，seed 原样包含。
internal fun offlineHas(lower: String, seed: String, keys: List<String>): Boolean =
    keys.any { key -> key.lowercase() in lower || key in seed }
