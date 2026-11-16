package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Emotion

// 情绪感知回复的语气映射：固定 Emotion -> tone，供服务端回复生成用；
// 与 AiReplyTonePolicy.normalizeTone 不同（后者归一化用户输入语气）。
object AiEmotionTonePolicy {

    fun toneFor(emotion: Emotion): String =
        when (emotion) {
            Emotion.HAPPY -> "warm"
            Emotion.SAD -> "empathetic"
            Emotion.ANGRY -> "gentle"
            Emotion.ANXIOUS -> "encouraging"
            Emotion.NEUTRAL -> "friendly"
        }
}
