package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiImageAnalysisMode

// AI 图片分析模式种类；模式枚举本身在 :core:model，domain 只做 1:1 映射。
enum class AiImageAnalysisModeKind {
    DESCRIBE,
    OCR,
    SAFETY,
}

fun aiImageAnalysisModeKind(mode: AiImageAnalysisMode): AiImageAnalysisModeKind =
    when (mode) {
        AiImageAnalysisMode.DESCRIBE -> AiImageAnalysisModeKind.DESCRIBE
        AiImageAnalysisMode.OCR -> AiImageAnalysisModeKind.OCR
        AiImageAnalysisMode.SAFETY -> AiImageAnalysisModeKind.SAFETY
    }
