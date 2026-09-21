package com.maodouchat.webrtc

/**
 * WebRTC 统计报告里的两个纯计算（G176 从 WebRTCManager 抽出）。
 *
 * 抽出来的理由：`WebRTCManager` 是**成员式大类**（约 55 个成员方法，1431 行），
 * 整体拆分风险高；而这两个函数**不碰任何实例状态**，是文件里最干净的切分点。
 * 抽成顶层后普通 JVM 单测就能覆盖，不必再依赖仪器测试。
 */

/**
 * 把 RTCStats 的成员值读成 Double。
 *
 * WebRTC 的 statsMap 值是 `Any`：数字直接来，字符串要能解析，
 * 其余（null / Boolean / 嵌套 Map）一律 null——调用方据此跳过该字段。
 */
internal fun readStatNumber(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

/**
 * 丢包率百分比；`lost + received == 0` 时返回 null（还没有样本，不是 0%）。
 *
 * 先转 Double 再除，避免大数场景下 `lost * 100` 溢出 Long。
 */
internal fun packetLossPercent(lost: Long, received: Long): Double? {
    val total = lost + received
    if (total <= 0L) return null
    return lost.toDouble() * 100.0 / total.toDouble()
}
