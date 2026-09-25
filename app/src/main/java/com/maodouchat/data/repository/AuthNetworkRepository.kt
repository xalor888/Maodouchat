package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.AuthResponse

/**
 * 登录/注册/找回密码的**免鉴权**端点（G328c）。
 *
 * 为什么单独一类：这四个端点**不带令牌**（令牌还没拿到），
 * 与其它仓库的「带 token 的已登录请求」在调用前提上就不一样；
 * 混进 `AccountSecurityNetworkRepository`（那组全是「已登录后改账号」）会让
 * 「这里能不能不发 token」这个问题反复被问。
 *
 * ⚠️ `password` 会经由这里进到网络层：本类不做任何日志/缓存（薄转发），
 * 也不把密码写进异常信息。调用方负责用完即弃。
 *
 * 刻意很薄（不缓存、不重试）：登录成功与否必须实时；重试由用户决定。
 *
 * 会话**刷新**不在这里——那个要读写本机令牌存储，属于 `SessionNetworkRepository`。
 */
internal class AuthNetworkRepository(
    private val loginApi: suspend (String, String, String) -> Result<AuthResponse> =
        { email, password, totpCode -> ApiService.login(email, password, totpCode) },
    private val registerApi: suspend (String, String, String, String) -> Result<AuthResponse> =
        { name, email, password, code -> ApiService.registerWithCode(name, email, password, code) },
    private val sendCodeApi: suspend (String, String) -> Result<Unit> =
        { email, purpose -> ApiService.sendVerificationCode(email, purpose) },
    private val resetPasswordApi: suspend (String, String, String) -> Result<Unit> =
        { email, code, newPassword -> ApiService.resetPassword(email, code, newPassword) },
) {
    /** `totpCode` 空串 = 本次不需要两因子（服务端会回 `TOTP_REQUIRED` 让 UI 再问一次）。 */
    suspend fun login(email: String, password: String, totpCode: String = ""): Result<AuthResponse> =
        loginApi(email, password, totpCode)

    suspend fun register(name: String, email: String, password: String, code: String): Result<AuthResponse> =
        registerApi(name, email, password, code)

    /** 发邮箱验证码。`purpose` 区分注册/找回密码等用途。 */
    suspend fun sendVerificationCode(email: String, purpose: String = "register"): Result<Unit> =
        sendCodeApi(email, purpose)

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> =
        resetPasswordApi(email, code, newPassword)
}
