package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.IdentityVerificationText

/** domain 侧 IdentityVerificationText 到 app 字符串资源的映射。 */
internal fun identityVerificationTextResId(key: IdentityVerificationText): Int = when (key) {
    IdentityVerificationText.IDENTITY_CHANGED_WARNING -> R.string.chat_identity_changed_warning
    IdentityVerificationText.IDENTITY_UNKNOWN_WARNING -> R.string.chat_identity_unknown_warning
    IdentityVerificationText.IDENTITY_TRUSTED_WARNING -> R.string.chat_identity_trusted_warning
    IdentityVerificationText.SAFETY_DEVICES_LOAD_FAILED -> R.string.chat_safety_devices_load_failed
    IdentityVerificationText.SAFETY_CODE_DISABLED -> R.string.safety_code_disabled
    IdentityVerificationText.TRUST_FAILED -> R.string.contacts_safety_trust_failed
}
