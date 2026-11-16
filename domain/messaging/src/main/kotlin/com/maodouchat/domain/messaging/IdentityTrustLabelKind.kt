package com.maodouchat.domain.messaging

import com.maodouchat.core.model.IdentityTrustState

// 安全码信任态文案种类；文案资源 id 留 app 侧，domain 只做状态→种类映射。
enum class IdentityTrustLabelKind {
    UNKNOWN,
    TRUSTED,
    VERIFIED,
    CHANGED,
}

fun identityTrustLabelKind(state: IdentityTrustState): IdentityTrustLabelKind =
    when (state) {
        IdentityTrustState.UNKNOWN -> IdentityTrustLabelKind.UNKNOWN
        IdentityTrustState.TRUSTED -> IdentityTrustLabelKind.TRUSTED
        IdentityTrustState.VERIFIED -> IdentityTrustLabelKind.VERIFIED
        IdentityTrustState.CHANGED -> IdentityTrustLabelKind.CHANGED
    }
