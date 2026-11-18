package com.maodouchat.domain.messaging


// 定位失败文案种类；R.string 映射留在 app 侧。null 落入 UNKNOWN，
// 调用点与 UNAVAILABLE 共用同一文案，与原 `UNAVAILABLE, null` 分支一致。
enum class LocationFailureKind {
    PERMISSION_REQUIRED,
    SERVICES_DISABLED,
    UNAVAILABLE,
    UNKNOWN,
}

fun locationFailureKind(failure: LocationFailure?): LocationFailureKind =
    when (failure) {
        LocationFailure.PERMISSION_REQUIRED -> LocationFailureKind.PERMISSION_REQUIRED
        LocationFailure.SERVICES_DISABLED -> LocationFailureKind.SERVICES_DISABLED
        LocationFailure.UNAVAILABLE -> LocationFailureKind.UNAVAILABLE
        null -> LocationFailureKind.UNKNOWN
    }
