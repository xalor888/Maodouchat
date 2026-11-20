package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType

// CallType → Android 运行时权限字符串；值与 android.Manifest.permission.* 逐字相同，
// domain 侧只拼字符串，不依赖 Android 框架。
object CallPermissionStrings {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val CAMERA = "android.permission.CAMERA"

    fun requiredPermissions(callType: CallType): Array<String> =
        CallReliabilityPolicy.requiredPermissions(callType).map { permission ->
            when (permission) {
                CallMediaPermission.MICROPHONE -> RECORD_AUDIO
                CallMediaPermission.CAMERA -> CAMERA
            }
        }.toTypedArray()
}
