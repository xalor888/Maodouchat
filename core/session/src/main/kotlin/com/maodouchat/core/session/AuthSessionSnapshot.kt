package com.maodouchat.core.session

/**
 * 冻结会话上下文：认证会话的最小稳定快照，供领域层只读注入。
 *
 * G328c 改名说明：它原先叫 `SessionContext`，而 `app` 侧另有一个同名的
 * `com.maodouchat.session.SessionContext`（字段是 `ownerUserId`/`generation`，语义是
 * 「工作不能跨认证周期」的归属句柄）。两者字段与用途都不同，同名只在跨模块阅读时制造混淆，
 * 故保留 app 侧那个（生产在用），把本模块这个改成 [AuthSessionSnapshot]。
 */

data class AuthSessionSnapshot(
    val userId: String,
    val deviceId: Int,
) {
    companion object {
        val EMPTY = AuthSessionSnapshot(userId = "", deviceId = 0)
    }
}
