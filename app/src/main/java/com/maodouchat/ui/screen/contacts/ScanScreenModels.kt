package com.maodouchat.ui.screen.contacts

import com.maodouchat.security.SafetyScanStatus
import com.maodouchat.util.QrCodeGenerator

internal data class SafetyScanResult(
    val target: QrCodeGenerator.QrTarget.Safety,
    val status: SafetyScanStatus
) {
    val matched: Boolean
        get() = status == SafetyScanStatus.FINGERPRINT_MATCH || status == SafetyScanStatus.CODE_MATCH
}
