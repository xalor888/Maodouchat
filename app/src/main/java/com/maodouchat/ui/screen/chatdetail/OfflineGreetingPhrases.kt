package com.maodouchat.ui.screen.chatdetail

// 离线话术：问候/寒暄/情感簇（含疑问句优先分支）。
internal object OfflineGreetingPhrases {
    fun match(lower: String, seed: String): List<String>? {
        if ("?" in seed || "？" in seed || lower.startsWith("why") || lower.startsWith("how") || "吗" in seed || "么" in seed || "呢" in seed) return listOf(
            "好问题，我的想法是……",
            "可以，稍等我整理一下再回你。",
            "我更倾向这个方向，你觉得呢？"
        )
        if (offlineHas(lower, seed, listOf("谢谢", "thanks", "thank"))) return listOf(
            "不客气～",
            "应该的，有需要再叫我。",
            "小事一桩。"
        )
        if (offlineHas(lower, seed, listOf("你好", "hello", "hi ", "在吗", "在不在"))) return listOf(
            "在的，怎么啦？",
            "嗨，刚看到～",
            "在呢，说吧。"
        )
        if (offlineHas(lower, seed, listOf("约", "见面", "吃饭", "电影"))) return listOf(
            "时间地点你定，我配合～",
            "可以啊，周末怎么样？",
            "我想去，细节再敲定。"
        )
        if (offlineHas(lower, seed, listOf("难过", "伤心", "累", "压力"))) return listOf(
            "我在这儿听你说。",
            "辛苦了，先歇一会儿吧。",
            "需要的话我陪你聊聊。"
        )
        if (offlineHas(lower, seed, listOf("ok", "okay", "good night", "gn", "晚安", "好的", "行"))) return listOf(
            "好的，收到。",
            "嗯嗯，晚点聊。",
            "Alright, talk soon."
        )
        if (offlineHas(lower, seed, listOf("sorry", "抱歉", "不好意思", "对不起"))) return listOf(
            "没关系。",
            "理解你，没事的。",
            "It's okay, no worries."
        )
        if (offlineHas(lower, seed, listOf("love", "喜欢", "爱你", "么么"))) return listOf(
            "我也是～",
            "收到满满的喜欢。",
            "同样的感觉。"
        )
        return null
    }
}
