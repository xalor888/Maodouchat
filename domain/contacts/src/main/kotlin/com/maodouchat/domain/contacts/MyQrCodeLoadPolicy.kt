package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User

/** 「我的二维码」页的加载判定：纯函数，无 Coroutine / Room / ApiService / Android 资源。 */
object MyQrCodeLoadPolicy {

    fun plan(
        qrEnabled: Boolean,
        token: String,
        userId: String,
        local: User?,
        remote: Result<User>?,
        sessionMayContinue: Boolean = true,
    ): MyQrCodeDecision {
        if (!qrEnabled) return MyQrCodeDecision.Reject(RejectReason.DISABLED)
        if (token.isBlank() && userId.isBlank()) {
            return MyQrCodeDecision.Reject(RejectReason.NO_SESSION)
        }
        // 门禁不通过（已登出/切号）时不得采用远端资料——那可能是下一个账号的
        val live = remote?.getOrNull()?.takeIf { sessionMayContinue }
        val name = live?.name?.takeIf { it.isNotBlank() } ?: local?.name.orEmpty()
        val avatar = live?.avatar ?: local?.avatar
        return MyQrCodeDecision.Allow(
            targetUserId = userId,
            displayName = name,
            avatar = avatar,
        )
    }
}
