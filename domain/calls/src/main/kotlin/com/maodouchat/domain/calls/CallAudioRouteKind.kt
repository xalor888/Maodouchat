package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallAudioRoute

// 通话音频输出路由文案种类；R.string 映射留在 app 侧；图标映射留在 app 侧（Compose Icons）。
enum class CallAudioRouteKind {
    BLUETOOTH,
    WIRED,
    EARPIECE,
    SPEAKER,
}

fun callAudioRouteKind(route: CallAudioRoute): CallAudioRouteKind =
    when (route) {
        CallAudioRoute.BLUETOOTH -> CallAudioRouteKind.BLUETOOTH
        CallAudioRoute.WIRED -> CallAudioRouteKind.WIRED
        CallAudioRoute.EARPIECE -> CallAudioRouteKind.EARPIECE
        CallAudioRoute.SPEAKER -> CallAudioRouteKind.SPEAKER
    }
