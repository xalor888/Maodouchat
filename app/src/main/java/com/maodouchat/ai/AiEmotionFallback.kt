package com.maodouchat.ai

import android.content.Context
import com.maodouchat.R

/** 情绪感知回复的本地回退模板簇：服务端不可用/未同意时回退，不消费 AI 预算。 */
internal object AiEmotionFallback {

    // 8.48：本地回退模板国际化（此前硬编码中文，英文用户看到中文回复）。不声称执行任何动作，仅表达理解。
    fun localFallback(context: Context, emotion: AiEmotionReply.Emotion): String = when (emotion) {
        AiEmotionReply.Emotion.HAPPY -> context.getString(R.string.ai_emotion_fallback_happy)
        AiEmotionReply.Emotion.SAD -> context.getString(R.string.ai_emotion_fallback_sad)
        AiEmotionReply.Emotion.ANGRY -> context.getString(R.string.ai_emotion_fallback_angry)
        AiEmotionReply.Emotion.ANXIOUS -> context.getString(R.string.ai_emotion_fallback_anxious)
        AiEmotionReply.Emotion.NEUTRAL -> context.getString(R.string.ai_emotion_fallback_neutral)
    }
}
