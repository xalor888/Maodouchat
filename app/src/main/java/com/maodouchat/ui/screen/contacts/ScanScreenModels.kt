package com.maodouchat.ui.screen.contacts

import com.maodouchat.core.model.QrTarget
import com.maodouchat.core.session.SafetyScanStatus

internal data class SafetyScanResult(
    val target: QrTarget.Safety,
    val status: SafetyScanStatus
) {
    val matched: Boolean
        get() = status == SafetyScanStatus.FINGERPRINT_MATCH || status == SafetyScanStatus.CODE_MATCH
}
