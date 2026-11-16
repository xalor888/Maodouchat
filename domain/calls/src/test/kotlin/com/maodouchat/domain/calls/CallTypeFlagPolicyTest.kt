package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.Flag
import kotlin.test.Test
import kotlin.test.assertEquals

class CallTypeFlagPolicyTest {

    @Test
    fun `video maps to video call flag`() {
        assertEquals(
            Flag("video_call_enabled", true),
            CallTypeFlagPolicy.flagFor(CallType.VIDEO),
        )
    }

    @Test
    fun `audio and group map to voice call flag`() {
        assertEquals(
            Flag("voice_call_enabled", true),
            CallTypeFlagPolicy.flagFor(CallType.AUDIO),
        )
        assertEquals(
            Flag("voice_call_enabled", true),
            CallTypeFlagPolicy.flagFor(CallType.GROUP),
        )
    }
}
