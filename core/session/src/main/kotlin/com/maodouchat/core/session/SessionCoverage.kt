package com.maodouchat.core.session

/**
 * 发现候选设备与独立单设备会话建立结果。
 */
data class SessionCoverage(
    val candidateDeviceIds: List<Int>,
    val establishedDeviceIds: List<Int>,
    val failuresByDevice: Map<Int, Throwable>,
)
