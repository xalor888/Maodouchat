package com.maodouchat.domain.messaging

// AI 图片分析提示词；mode 是调用方传的字符串，先归一化再选指令。
fun aiImageAnalysisInstruction(mode: String): String =
    when (mode.trim().lowercase()) {
        "ocr" -> "只提取图中文字，按阅读顺序输出。没有文字就输出空。"
        "risk" -> "简述图中可见风险（钓鱼、二维码、证件）。不要声称已执行任何操作。"
        else -> "用一两段话描述这张图。不要声称已发送或已删除消息。"
    }
