package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallAudioRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioRouteKindTest {

    @Test
    fun `all four routes map one to one`() {
        assertEquals(CallAudioRouteKind.BLUETOOTH, callAudioRouteKind(CallAudioRoute.BLUETOOTH))
        assertEquals(CallAudioRouteKind.WIRED, callAudioRouteKind(CallAudioRoute.WIRED))
        assertEquals(CallAudioRouteKind.EARPIECE, callAudioRouteKind(CallAudioRoute.EARPIECE))
        assertEquals(CallAudioRouteKind.SPEAKER, callAudioRouteKind(CallAudioRoute.SPEAKER))
    }
}
