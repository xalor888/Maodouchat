package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiFileAnalysisMode

// AI 文件分析模式种类；模式枚举本身在 :core:model，domain 只做 1:1 映射。
enum class AiFileAnalysisModeKind {
    SUMMARIZE,
    QUESTION,
}

fun aiFileAnalysisModeKind(mode: AiFileAnalysisMode): AiFileAnalysisModeKind =
    when (mode) {
        AiFileAnalysisMode.SUMMARIZE -> AiFileAnalysisModeKind.SUMMARIZE
        AiFileAnalysisMode.QUESTION -> AiFileAnalysisModeKind.QUESTION
    }
