package com.maodouchat.ui.screen.contacts

import com.maodouchat.core.model.MyQrCodeDecision
import com.maodouchat.core.model.RejectReason
import com.maodouchat.core.model.User

/**
 * 「我的二维码」页的加载判定门面：plan() 已迁入 :core:model，这里留同名 internal 委托
 *（调用方 MyQrCodeViewModel 与测试暂不动，后续轮次拆除）；二维码生成收尾 finalize() 含
 * Bitmap，仍在 app 侧。
 */
internal object MyQrCodeLoadPolicy {

    fun plan(
        qrEnabled: Boolean,
        token: String,
        userId: String,
        local: User?,
        remote: Result<User>?,
        sessionMayContinue: Boolean = true,
    ): MyQrCodeDecision =
        com.maodouchat.core.model.MyQrCodeLoadPolicy.plan(
            qrEnabled = qrEnabled,
            token = token,
            userId = userId,
            local = local,
            remote = remote,
            sessionMayContinue = sessionMayContinue,
        )

    /** 二维码生成收尾：null bitmap 是失败，不是崩溃。 */
    fun finalize(bitmap: android.graphics.Bitmap?): FinalizeResult =
        if (bitmap == null) {
            FinalizeResult(success = false, bitmap = null, message = "generation_failed")
        } else {
            FinalizeResult(success = true, bitmap = bitmap, message = null)
        }
}
