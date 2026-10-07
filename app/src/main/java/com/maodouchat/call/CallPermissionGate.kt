package com.maodouchat.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.maodouchat.webrtc.CallMediaPermission
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallType

/**
 * 通话权限的唯一入口：各入口（来电路由、去电目的地、聊天页通话按钮）不再各自硬编码权限集合，
 * 全部走这里查「某通话类型需要哪些 Android 运行时权限」。
 */
object CallPermissionGate {

    /** CallType → Android 运行时权限；唯一来源是 webrtc 的 CallReliabilityPolicy。 */
    fun requiredPermissions(callType: CallType): Array<String> =
        CallReliabilityPolicy.requiredPermissions(callType).map { permission ->
            when (permission) {
                CallMediaPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                CallMediaPermission.CAMERA -> Manifest.permission.CAMERA
            }
        }.toTypedArray()

    fun hasPermissions(context: Context, callType: CallType): Boolean =
        requiredPermissions(callType).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 当前缺失的子集；空数组表示全部已授权。 */
    fun missingPermissions(context: Context, callType: CallType): Array<String> =
        requiredPermissions(callType).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /**
     * 已全部授权则直接 onGranted，否则只把缺失子集交给 launcher。
     * launcher 由调用方在 UI 层注册（Activity 结果回调），这里只管策略。
     */
    fun ensurePermissions(
        context: Context,
        callType: CallType,
        launch: (Array<String>) -> Unit,
        onGranted: () -> Unit
    ) {
        val missing = missingPermissions(context, callType)
        if (missing.isEmpty()) onGranted() else launch(missing)
    }
}
