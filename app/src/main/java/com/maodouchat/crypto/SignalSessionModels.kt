package com.maodouchat.crypto

/**
 * 发现候选设备与独立单设备会话建立结果。
 */
internal data class SessionCoverage(
    val candidateDeviceIds: List<Int>,
    val establishedDeviceIds: List<Int>,
    val failuresByDevice: Map<Int, Throwable>,
)
