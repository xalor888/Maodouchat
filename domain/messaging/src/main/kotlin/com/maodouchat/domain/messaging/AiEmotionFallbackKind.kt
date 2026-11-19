package com.maodouchat.domain.messaging

// 情绪回退模板种类；R.string 映射留在 app 侧。与 AiEmotionTonePolicy 的语气映射不同，
// 这里只定回退用哪条模板。
enum class AiEmotionFallbackKind {
    HAPPY,
    SAD,
    ANGRY,
    ANXIOUS,
    NEUTRAL,
}

fun aiEmotionFallbackKind(emotion: Emotion): AiEmotionFallbackKind =
    when (emotion) {
        Emotion.HAPPY -> AiEmotionFallbackKind.HAPPY
        Emotion.SAD -> AiEmotionFallbackKind.SAD
        Emotion.ANGRY -> AiEmotionFallbackKind.ANGRY
        Emotion.ANXIOUS -> AiEmotionFallbackKind.ANXIOUS
        Emotion.NEUTRAL -> AiEmotionFallbackKind.NEUTRAL
    }
