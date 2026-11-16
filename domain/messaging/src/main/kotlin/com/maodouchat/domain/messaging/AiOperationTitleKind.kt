package com.maodouchat.domain.messaging

// AI 操作标题种类；操作类型是 wire 字符串（AiOperationUi.type），
// 未知值与原 else 一致归为 GENERIC。
enum class AiOperationTitleKind {
    TRANSCRIBE_VOICE,
    TRANSLATE_MESSAGE,
    SUMMARIZE_MESSAGES,
    ANALYZE_IMAGE,
    ANALYZE_FILE,
    GENERIC,
}

fun aiOperationTitleKind(type: String): AiOperationTitleKind =
    when (type) {
        "TRANSCRIBE_VOICE" -> AiOperationTitleKind.TRANSCRIBE_VOICE
        "TRANSLATE_MESSAGE" -> AiOperationTitleKind.TRANSLATE_MESSAGE
        "SUMMARIZE_MESSAGES" -> AiOperationTitleKind.SUMMARIZE_MESSAGES
        "ANALYZE_IMAGE" -> AiOperationTitleKind.ANALYZE_IMAGE
        "ANALYZE_FILE" -> AiOperationTitleKind.ANALYZE_FILE
        else -> AiOperationTitleKind.GENERIC
    }
