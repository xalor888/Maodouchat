package com.maodouchat.core.session

/**
 * 冻结会话上下文：认证会话的最小稳定快照，供领域层只读注入。
 *
 * G328c 改名说明：它原先叫 `SessionContext`，而另有一个同名的会话归属句柄
 * （字段是 `ownerUserId`/`generation`，语义是「工作不能跨认证周期」）。
 * 两者字段与用途都不同，同名只在跨模块阅读时制造混淆，故本模块这个改成
 * [AuthSessionSnapshot]；那个句柄自 §4.1 首拆起也迁入本模块，见 [SessionContext]。
 */

data class AuthSessionSnapshot(
    val userId: String,
    val deviceId: Int,
) {
    companion object {
        val EMPTY = AuthSessionSnapshot(userId = "", deviceId = 0)
    }
}
