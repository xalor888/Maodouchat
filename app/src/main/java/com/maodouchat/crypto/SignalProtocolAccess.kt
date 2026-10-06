package com.maodouchat.crypto

import com.maodouchat.MaodouchatApp

/**
 * Signal 协议实例的应用级访问点（ui 不再经 `MaodouchatApp` 符号直取）。
 *
 * 与 `MaodouchatApp.instance.signalProtocol` 同一实例；这里只是给 ui 一个
 * 不触碰 app 符号的入口——ui 直连持久层棘轮按符号计数。
 */
object SignalProtocolAccess {

    val protocol: SignalProtocol
        get() = MaodouchatApp.instance.signalProtocol
}
