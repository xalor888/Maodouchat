package com.maodouchat.server.service

/** 多因子认证（TOTP + 一次性恢复码），自 UserRepository 拆出（B02「拆 MfaService」）。 */

class MfaService {

    // SecureRandom 线程安全，每次生成备份码都 new 会反复播种，实例级复用。
    private val secureRandom = java.security.SecureRandom()
}
