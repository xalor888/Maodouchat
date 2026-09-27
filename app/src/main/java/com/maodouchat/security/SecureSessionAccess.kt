package com.maodouchat.security

import com.maodouchat.MaodouchatApp

/**
 * 安全会话管理器（登出清理 / 会话世代）的非 ui 入口（U02 延伸）。
 *
 * `MaodouchatApp.instance.secureSessionManager` 目前被若干 ui 调用点直接取用
 * （设置族本轮先迁；其余随各自批次）。本对象是唯一入口，迁完的调用点即从
 * ui 直连持久层棘轮退出。
 */
object SecureSessionAccess {

    val manager: SecureSessionManager
        get() = MaodouchatApp.instance.secureSessionManager
}
