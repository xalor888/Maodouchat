package com.maodouchat.domain.messaging

/** 身份核验控制器用到的文案键；app 侧由 `identityVerificationTextResId` 映射到 R。 */
enum class IdentityVerificationText {
    IDENTITY_CHANGED_WARNING,
    IDENTITY_UNKNOWN_WARNING,
    IDENTITY_TRUSTED_WARNING,
    SAFETY_DEVICES_LOAD_FAILED,
    SAFETY_CODE_DISABLED,
    TRUST_FAILED,
}
