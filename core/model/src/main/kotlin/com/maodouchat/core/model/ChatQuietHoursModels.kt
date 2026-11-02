package com.maodouchat.core.model

data class QuietWindow(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int
) {
    companion object {
        val OFF = QuietWindow(false, 0, 0)
    }
}
