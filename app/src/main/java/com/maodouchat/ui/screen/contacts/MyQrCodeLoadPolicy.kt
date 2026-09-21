package com.maodouchat.ui.screen.contacts

import com.maodouchat.data.model.User

/**
 * 「我的二维码」页的加载判定（G72，从 `MyQrCodeViewModel.load()` 里抽出）。
 *
 * **为什么抽它**：改造前这个 ViewModel **一个用例都没有**，而它正是 G71 门禁名单里
 * 「直接抓应用级数据库单例构造 Repository」的那一处。要动它的装配方式，
 * 就得先把「装配之外的行为」钉住，否则改完只能靠手点。
 *
 * 本对象是纯函数：无 Coroutine / Room / ApiService / Android 资源。
 */
internal object MyQrCodeLoadPolicy {

    enum class RejectReason {
        DISABLED,
        NO_SESSION,
    }

    sealed interface Decision {
        data class Reject(val reason: RejectReason) : Decision
        data class Allow(
            /** 二维码编码的目标 userId（不是昵称）。 */
            val targetUserId: String,
            val displayName: String,
            val avatar: String?,
        ) : Decision
    }

    data class FinalizeResult(
        val success: Boolean,
        val bitmap: android.graphics.Bitmap?,
        val message: String?,
    )

    /**
     * @param qrEnabled 功能开关（`RuntimeFlags.QR_CODE`）
     * @param token / userId 当前会话
     * @param local 本地缓存的用户（可能为 null）
     * @param remote 在线获取结果；null 表示「没有尝试」（例如门禁不通过或没有 token）
     * @param sessionMayContinue 会话门禁（已登出/切号时为 false）
     */
    fun plan(
        qrEnabled: Boolean,
        token: String,
        userId: String,
        local: User?,
        remote: Result<User>?,
        sessionMayContinue: Boolean = true,
    ): Decision {
        if (!qrEnabled) return Decision.Reject(RejectReason.DISABLED)
        if (token.isBlank() && userId.isBlank()) {
            return Decision.Reject(RejectReason.NO_SESSION)
        }
        // 门禁不通过（已登出/切号）时不得采用远端资料——那可能是下一个账号的
        val live = remote?.getOrNull()?.takeIf { sessionMayContinue }
        val name = live?.name?.takeIf { it.isNotBlank() } ?: local?.name.orEmpty()
        val avatar = live?.avatar ?: local?.avatar
        return Decision.Allow(
            targetUserId = userId,
            displayName = name,
            avatar = avatar,
        )
    }

    /** 二维码生成收尾：null bitmap 是失败，不是崩溃。 */
    fun finalize(bitmap: android.graphics.Bitmap?): FinalizeResult =
        if (bitmap == null) {
            FinalizeResult(success = false, bitmap = null, message = "generation_failed")
        } else {
            FinalizeResult(success = true, bitmap = bitmap, message = null)
        }
}
