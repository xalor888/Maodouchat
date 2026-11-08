package com.maodouchat.core.model

/** 身份信任状态（M03）：UNKNOWN 未验证 / TRUSTED 已建会话但未核对安全码 / VERIFIED 已验证安全码 / CHANGED 对方身份密钥已变更。 */
enum class IdentityTrustState { UNKNOWN, TRUSTED, VERIFIED, CHANGED }
