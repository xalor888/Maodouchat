package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.SignalProtocolConstants
import com.maodouchat.core.model.IdentityTrustState

// 身份信任字符串→信任状态：VERIFIED/CHANGED 逐字映射，其余落入 TRUSTED。
object IdentityTrustStatePolicy {

    fun stateFor(trustState: String): IdentityTrustState =
        when (trustState) {
            SignalProtocolConstants.TRUST_VERIFIED -> IdentityTrustState.VERIFIED
            SignalProtocolConstants.TRUST_CHANGED -> IdentityTrustState.CHANGED
            else -> IdentityTrustState.TRUSTED
        }
}
