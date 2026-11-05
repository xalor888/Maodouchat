package com.maodouchat.core.session

import com.maodouchat.core.model.QrTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyScanResultTest {

    private fun target() = QrTarget.Safety(
        ownerUserId = "u0",
        ownerDeviceId = 3,
        peerUserId = "u1",
        peerDeviceId = 7,
    )

    private fun result(status: SafetyScanStatus) = SafetyScanResult(target(), status)

    @Test
    fun fingerprintMatchIsMatched() {
        assertTrue(result(SafetyScanStatus.FINGERPRINT_MATCH).matched)
    }

    @Test
    fun codeMatchIsMatched() {
        assertTrue(result(SafetyScanStatus.CODE_MATCH).matched)
    }

    @Test
    fun otherStatusesAreNotMatched() {
        val others = SafetyScanStatus.entries.filter {
            it != SafetyScanStatus.FINGERPRINT_MATCH && it != SafetyScanStatus.CODE_MATCH
        }
        assertEquals(8, others.size)
        others.forEach { assertFalse(result(it).matched) }
    }
}
