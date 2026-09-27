package com.maodouchat.network

import com.maodouchat.MaodouchatApp

/**
 * 切换服务器成功后的进程内重建（U02 延伸：自 `ui/screen/settings/SettingsServer`
 * 的 app 单例直连收口）。
 *
 * 顺序与作用逐字对齐原实现（两处「保存并切换」成功分支）：
 * 1. `rebuildImageLoader()`——旧 base URL 下的图片缓存/请求器作废；
 * 2. `disconnectRealtime()`——旧服 WS 断开，由既有重连策略按新服重建。
 *
 * 注意：调用方在之后还要做 `OnDemandStickerStore.invalidateServerState()`、
 * `ServerIdentity.refreshAsync()` 与 `onServerChanged()`——那些**不**属于本收口，
 * 保持在各调用点原位（本类只承载 app 单例上的两步）。
 */
object ServerSwitchEffects {

    fun applyAfterServerChanged() {
        MaodouchatApp.instance.rebuildImageLoader()
        MaodouchatApp.instance.disconnectRealtime()
    }
}
