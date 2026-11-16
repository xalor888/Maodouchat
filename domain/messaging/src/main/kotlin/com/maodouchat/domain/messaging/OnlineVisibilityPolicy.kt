package com.maodouchat.domain.messaging

// 在线可见性归一化；非法值与原 else 一致回落 everyone。
fun normalizeOnlineVisibility(visibility: String): String =
    when (visibility) {
        "contacts", "nobody" -> visibility
        else -> "everyone"
    }
