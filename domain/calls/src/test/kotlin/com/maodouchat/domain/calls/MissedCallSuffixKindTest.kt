package com.maodouchat.domain.calls

import com.maodouchat.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals

class MissedCallSuffixKindTest {

    @Test
    fun `missed calls show the badge`() {
        assertEquals(MissedCallSuffixKind.BADGE, missedCallSuffixKind(State.MISSED, ""))
        assertEquals(MissedCallSuffixKind.BADGE, missedCallSuffixKind(State.MISSED, "03:12"))
    }

    @Test
    fun `answered calls with a duration show it`() {
        assertEquals(MissedCallSuffixKind.DURATION, missedCallSuffixKind(State.ANSWERED, "03:12"))
    }

    @Test
    fun `answered calls without a duration show nothing`() {
        assertEquals(MissedCallSuffixKind.NONE, missedCallSuffixKind(State.ANSWERED, ""))
    }
}
