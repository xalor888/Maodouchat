package com.maodouchat.core.crypto

/** 身份信任状态（M03）：UNKNOWN 未验证 / VERIFIED 已验证安全码 / CHANGED 对方身份密钥已变更。 */
enum class IdentityTrustState { UNKNOWN, VERIFIED, CHANGED }

/**
 * 身份信任状态机（纯逻辑，无 libsignal / Android 依赖）。
 * 对应旧 `TRUST_VERIFIED`/`TRUST_CHANGED` 语义，抽离为可测试纯类。
 */
object IdentityTrustStateMachine {
    /** 用户核对安全码 → 标记已验证（任何状态收敛到 VERIFIED）。 */
    fun onVerification(current: IdentityTrustState): IdentityTrustState = IdentityTrustState.VERIFIED

    /** 对方身份密钥变更 → 仅「已验证」降级为「已变更」；其余状态保持不变。 */
    fun onIdentityKeyChange(current: IdentityTrustState): IdentityTrustState = when (current) {
        IdentityTrustState.VERIFIED -> IdentityTrustState.CHANGED
        else -> current
    }

    /** 重置（删除会话/换设备）→ UNKNOWN。 */
    fun onReset(): IdentityTrustState = IdentityTrustState.UNKNOWN
}

/** 身份信任服务契约（M03）：页面/Widget/AI 只依赖此端口，不直接碰 libsignal。 */
interface IdentityTrustService {
    fun stateFor(peerAccountId: String, deviceId: Int): IdentityTrustState
    fun markVerified(peerAccountId: String, deviceId: Int): IdentityTrustState
    fun onIdentityKeyChanged(peerAccountId: String, deviceId: Int): IdentityTrustState
    fun reset(peerAccountId: String, deviceId: Int): IdentityTrustState
}
