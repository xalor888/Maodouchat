package com.maodouchat.watermark

import android.content.Context

/**
 * 盲水印载荷的「设备提示」（deviceHint）读取点。
 *
 * 全部 8 个调用点此前各自内联 `Settings.Secure.getString(contentResolver, ANDROID_ID)`，
 * lint 的 `HardwareIds` 逐处告警；`ANDROID_ID` 是按应用作用域、可重置的标识（非硬件序列号），
 * 且这里的用途只是水印载荷的抗裁剪提示——集中到本对象并在此豁免一次，
 * 调用点（含两个零松量行数上限文件）保持零散告警。
 */
object DeviceHint {

    /** @return ANDROID_ID；读取失败返回 null（调用方按空提示处理）。 */
    @Suppress("HardwareIds") // ANDROID_ID 为按应用作用域、可重置标识；集中豁免，理由见 KDoc
    fun androidId(context: Context): String? =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
}
