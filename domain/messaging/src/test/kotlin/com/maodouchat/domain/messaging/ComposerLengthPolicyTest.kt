package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerLengthPolicyTest {

    @Test
    fun `clip truncates to 4000 chars`() {
        assertEquals("a".repeat(4000), ComposerLengthPolicy.clip("a".repeat(4001)))
        assertEquals("abc", ComposerLengthPolicy.clip("abc"))
        assertEquals("", ComposerLengthPolicy.clip(""))
    }

    @Test
    fun `warningLevel follows 80 and 90 percent thresholds`() {
        assertEquals(ComposerLengthWarning.NONE, ComposerLengthPolicy.warningLevel(3199))
        assertEquals(ComposerLengthWarning.APPROACHING, ComposerLengthPolicy.warningLevel(3200))
        assertEquals(ComposerLengthWarning.APPROACHING, ComposerLengthPolicy.warningLevel(3599))
        assertEquals(ComposerLengthWarning.NEAR_LIMIT, ComposerLengthPolicy.warningLevel(3600))
        assertEquals(ComposerLengthWarning.NEAR_LIMIT, ComposerLengthPolicy.warningLevel(4000))
    }

    @Test
    fun `max length is 4000`() {
        assertEquals(4000, ComposerLengthPolicy.MAX_LENGTH)
    }
}
