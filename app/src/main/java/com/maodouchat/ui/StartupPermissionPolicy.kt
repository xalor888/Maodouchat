package com.maodouchat.ui

/**
 * P08：冷启动权限清单决策（纯逻辑，无 Android 依赖）。
 *
 * Activity 只负责检查授权态与发起 [ActivityResult]；录音/相机等按功能入口另请。
 */
object StartupPermissionPolicy {
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    /** Android 13 (Tiramisu) API level. */
    const val TIRAMISU_SDK = 33

    /**
     * @param sdkInt [android.os.Build.VERSION.SDK_INT]
     * @param isGranted 已授予则 true
     */
    fun permissionsToRequest(sdkInt: Int, isGranted: (String) -> Boolean): List<String> {
        if (sdkInt < TIRAMISU_SDK) return emptyList()
        return if (isGranted(POST_NOTIFICATIONS)) emptyList()
        else listOf(POST_NOTIFICATIONS)
    }
}
