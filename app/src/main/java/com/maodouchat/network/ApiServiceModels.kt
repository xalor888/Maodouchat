package com.maodouchat.network

/**
 * Token 过期事件 — 当 API 返回 401 时发射，UI 层监听后清除 Token 并跳转登录页
 */
data class TokenExpiredEvent(
    val ownerUserId: String,
    val sessionGeneration: Long,
)
