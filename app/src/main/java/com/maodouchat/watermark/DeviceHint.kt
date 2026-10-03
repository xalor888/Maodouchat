package com.maodouchat.watermark

import android.content.Context

object DeviceHint {

    /** @return ANDROID_ID；读取失败返回 null（调用方按空提示处理）。 */
    @Suppress("HardwareIds") // ANDROID_ID 为按应用作用域、可重置标识；集中豁免，理由见 KDoc
    fun androidId(context: Context): String? =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
}
