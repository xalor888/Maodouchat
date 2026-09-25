package com.maodouchat.session

import com.maodouchat.network.TokenManager

/**
 * **当前账号的身份与凭据**——进程内唯一的读取点。
 *
 * 为什么要有它：`TokenManager` 既是凭据存储又是身份来源，于是「我只想知道现在是谁在登录」
 * 的调用方（头像缓存按账号分目录、列表投影比对 owner、帖子页判断「这条是不是我发的」）
 * 也只好依赖整个 `com.maodouchat.network` 包。那些地方要的是**身份**，不是凭据；
 * 依赖面收窄到这里之后，它们拿不到令牌本身，也不会把网络层拖进渲染路径。
 *
 * 与 `BackgroundSessionGate` 的分工：门禁回答「这批活还能不能继续干」，
 * 本类只回答「当前是谁」。门禁的实时会话读的就是这里的 [snapshot]——
 * 全进程只有这一处知道「实时会话从哪来」，不会出现两份读法各不一致。
 *
 * ⚠️ `override` 只给 JVM 单测用（那里的 `TokenManager.getInstanceOrNull()` 是 null，
 * 因为单测没有 Android 上下文）。生产代码不要设置它：那等于把「当前是谁」
 * 变成可被任意代码改写的全局状态。
 */
object CurrentSession {

    /** 某一时刻的会话快照。两个值要么都有效，要么都是 null（无会话）。 */
    data class Snapshot(val token: String?, val userId: String?)

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
