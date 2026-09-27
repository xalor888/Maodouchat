package com.maodouchat.notification

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.repository.NotificationCenterRepository

/**
 * 通知中心仓库的应用级访问点（U02 延伸：自 `ui/screen/chatlist/NotificationCenterScreen`
 * 的 app 单例直连收口）。
 *
 * 与 `MaodouchatApp.instance.notificationCenter` 同一实例（那边是单例持有，
 * 这里只是给 ui 一个不触碰 app 单例符号的入口——ui 直连持久层棘轮按符号计数）。
 */
object NotificationCenterAccess {

    val repository: NotificationCenterRepository
        get() = MaodouchatApp.instance.notificationCenter
}
