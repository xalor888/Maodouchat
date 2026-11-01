package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ConditionalSessionSaveResultTest {

    @Test
    fun saveResult_hasExactlyThreeOutcomes() {
        assertEquals(
            setOf(
                ConditionalSessionSaveResult.SAVED,
                ConditionalSessionSaveResult.SESSION_CHANGED,
                ConditionalSessionSaveResult.WRITE_FAILED,
            ),
            ConditionalSessionSaveResult.entries.toSet(),
        )
    }
}
