package com.maodouchat.ai

/** 情绪检测词典簇：关键词/表情词典 + 纯本地规则检测，不引入 embedding。 */
object AiEmotionLexicon {

    /** 本地情绪检测（纯词典规则）。 */
    fun detectEmotion(texts: List<String>): AiEmotionReply.EmotionResult {
        var happy = 0; var sad = 0; var angry = 0; var anxious = 0
        var totalHits = 0
        for (text in texts) {
            val sample = text.take(LOCAL_SCAN_CHARS)
            val hits = HAPPY_HITS.count { sample.contains(it) }
            val sHits = SAD_HITS.count { sample.contains(it) }
            val aHits = ANGRY_HITS.count { sample.contains(it) }
            val nHits = ANXIOUS_HITS.count { sample.contains(it) }
            happy += hits; sad += sHits; angry += aHits; anxious += nHits
            totalHits += hits + sHits + aHits + nHits
        }
        val best = listOf(
            happy to AiEmotionReply.Emotion.HAPPY,
            sad to AiEmotionReply.Emotion.SAD,
            angry to AiEmotionReply.Emotion.ANGRY,
            anxious to AiEmotionReply.Emotion.ANXIOUS
        ).maxByOrNull { it.first }
        if (best == null || best.first == 0 || totalHits == 0) {
            return AiEmotionReply.EmotionResult(AiEmotionReply.Emotion.NEUTRAL, 0.0)
        }
        return AiEmotionReply.EmotionResult(best.second, best.first.toDouble() / totalHits)
    }

    private val HAPPY_HITS = listOf("哈哈", "哈哈哈", "开心", "太好了", "棒", "喜欢", "耶", "嘻嘻", "lol", "haha", "great", "happy", "love")
    private val SAD_HITS = listOf("难过", "伤心", "哭了", "难受", "遗憾", "失落", "emo", "sad", "cry", "miss", "后悔")
    private val ANGRY_HITS = listOf("气死", "生气", "愤怒", "烦死了", "可恶", "恶心", "滚", "吵", "angry", "mad", "hate", "烦")
    private val ANXIOUS_HITS = listOf("焦虑", "担心", "害怕", "紧张", "来不及", "怎么办", "压力", "失眠", "worried", "anxious", "nervous", "stress")

    private const val LOCAL_SCAN_CHARS = 200
}
