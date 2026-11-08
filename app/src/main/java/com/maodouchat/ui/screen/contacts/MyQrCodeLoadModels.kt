package com.maodouchat.ui.screen.contacts

internal data class FinalizeResult(
    val success: Boolean,
    val bitmap: android.graphics.Bitmap?,
    val message: String?,
)

/** 二维码生成收尾：null bitmap 是失败，不是崩溃。 */
internal fun finalizeQrCode(bitmap: android.graphics.Bitmap?): FinalizeResult =
    if (bitmap == null) {
        FinalizeResult(success = false, bitmap = null, message = "generation_failed")
    } else {
        FinalizeResult(success = true, bitmap = bitmap, message = null)
    }
