package com.maodouchat.domain.messaging

// AI 消息分类类别；R.string 映射留在 app 侧；未知类别与原 else 一致归为 OTHER。
enum class AiClassifyCategoryKind {
    NOTICE,
    TODO,
    FINANCE,
    STUDY,
    TECH,
    SOCIAL,
    OTHER,
}

fun aiClassifyCategoryKind(category: String): AiClassifyCategoryKind =
    when (category) {
        "notice" -> AiClassifyCategoryKind.NOTICE
        "todo" -> AiClassifyCategoryKind.TODO
        "finance" -> AiClassifyCategoryKind.FINANCE
        "study" -> AiClassifyCategoryKind.STUDY
        "tech" -> AiClassifyCategoryKind.TECH
        "social" -> AiClassifyCategoryKind.SOCIAL
        else -> AiClassifyCategoryKind.OTHER
    }
