package com.maodouchat.util

/**
 * 自定义状态 / 个性签名策略（纯函数）。
 * 与服务端 [MAX_STATUS_LENGTH]=80 对齐；空白视为清除。
 */
object CustomStatusPolicy {
    const val MAX_LENGTH = 80

    /**
     * 预设快捷状态 wire 值（与云端同步的原文）。
     * UI 用 [status_preset_*] 本地化标签展示；写入仍用 wire 值保证多端一致。
     */
    val PRESETS = listOf(
        "在线",
        "忙碌",
        "开会中",
        "请勿打扰",
        "马上回来",
        "休假中",
        "学习中",
        "通勤中",
        "专注中",
        "吃饭中",
        "旅游中",
        "运动中",
        "工作中",
        "通话中",
        "开车中",
        "游戏中",
        "睡觉中",
        "写作中",
        "出差中",
        "充电中",
        "听歌中",
        "阅读中",
        "观影中",
        "做饭中"
    )

    fun normalize(raw: String?): String =
        raw.orEmpty().trim().take(MAX_LENGTH)

    fun isValid(raw: String?): Boolean {
        val value = raw.orEmpty()
        return value.length <= MAX_LENGTH
    }

    /** 对他人可见时的展示文案；关闭隐私或空串返回空。 */
    fun visibleStatus(status: String?, showStatus: Boolean): String {
        if (!showStatus) return ""
        return normalize(status)
    }
}
