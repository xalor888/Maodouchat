package com.maodouchat.login

import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.SenderKeyRetryManager
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.security.SecureSessionManager

/**
 * 登录流程对应用级服务的访问口。`application as MaodouchatApp` 只发生在这里，
 * ui 层的 LoginViewModel 不再直接持有 app 单例（U02：ui 不直连持久层/全局单例）。
 */
object LoginAccess {

    val signalProtocol: SignalProtocol
        get() = MaodouchatApp.instance.signalProtocol

    val secureSessionManager: SecureSessionManager
        get() = MaodouchatApp.instance.secureSessionManager

    val notificationCenter: NotificationCenterRepository
        get() = MaodouchatApp.instance.notificationCenter

    val senderKeyRetryManager: SenderKeyRetryManager
        get() = MaodouchatApp.instance.senderKeyRetryManager
}
