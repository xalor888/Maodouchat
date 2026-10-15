package com.maodouchat.ai

enum class Emotion(val wire: String) {
    HAPPY("happy"),
    SAD("sad"),
    ANGRY("angry"),
    ANXIOUS("anxious"),
    NEUTRAL("neutral")
}

data class EmotionResult(val emotion: Emotion, val confidence: Double)
