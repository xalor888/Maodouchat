package com.maodouchat.core.session

import com.maodouchat.core.model.QrTarget

data class SafetyScanResult(
    val target: QrTarget.Safety,
    val status: SafetyScanStatus
) {
    val matched: Boolean
        get() = status == SafetyScanStatus.FINGERPRINT_MATCH || status == SafetyScanStatus.CODE_MATCH
}
