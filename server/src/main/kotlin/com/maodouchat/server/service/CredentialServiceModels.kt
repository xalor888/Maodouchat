package com.maodouchat.server.service

import com.maodouchat.server.model.UserResponse

data class LoginResult(
    val user: UserResponse?,
    val passwordOk: Boolean,
    val totpEnabled: Boolean,
    val totpOk: Boolean
)

/**
 * 管理后台换发会话前的二次确认结果。口令与第二因子分开报告，
 * 路由才能区分「口令错」与「动态码缺失/错误」并给出可操作的提示。
 */
data class AdminCredentialCheck(
    val passwordOk: Boolean,
    val secondFactorOk: Boolean,
    val totpEnabled: Boolean,
)
