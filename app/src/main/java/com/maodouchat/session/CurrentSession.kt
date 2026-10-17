package com.maodouchat.session

import com.maodouchat.network.TokenManager

// 当前账号身份的进程内唯一读取点：调用方只拿身份、拿不到令牌，网络层不会被拖进渲染路径。
// `override` 仅供 JVM 单测（无 Android 上下文时 TokenManager 为 null），生产代码勿设。
object CurrentSession {

    @Volatile
    internal var override: (() -> Snapshot)? = null

    /**
     * 读一次实时会话。
     *
     * 无会话时返回 `Snapshot(null, null)` 而不是抛异常：登出后仍有在途代码会调用它，
     * 那些路径本来就该按「没有会话」处理。
     */
    fun snapshot(): Snapshot {
        override?.let { return it() }
        val manager = TokenManager.getInstanceOrNull() ?: return Snapshot(null, null)
        return Snapshot(manager.getToken(), manager.getUserId())
    }

    /** 当前账号 id；无会话时空串（与 `TokenManager.getUserId().orEmpty()` 同样的约定）。 */
    fun ownerUserId(): String = snapshot().userId.orEmpty()

    /** 有没有会话。 */
    fun hasSession(): Boolean = ownerUserId().isNotBlank()
}
