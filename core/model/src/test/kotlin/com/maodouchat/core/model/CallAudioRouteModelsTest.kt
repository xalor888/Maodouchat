package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioRouteModelsTest {

    @Test
    fun `audio routes keep declaration order`() {
        assertEquals(
            listOf(
                CallAudioRoute.BLUETOOTH,
                CallAudioRoute.WIRED,
                CallAudioRoute.EARPIECE,
                CallAudioRoute.SPEAKER,
            ),
            CallAudioRoute.entries.toList(),
        )
    }

    @Test
    fun `valueOf round-trips every route`() {
        CallAudioRoute.entries.forEach {
            assertEquals(it, CallAudioRoute.valueOf(it.name))
        }
    }

    @Test
    fun `manual route wins when available`() {
        assertEquals(
            CallAudioRoute.EARPIECE,
            CallAudioRoutePolicy.selected(
                setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.EARPIECE),
                speakerPreferred = false,
                manualRoute = CallAudioRoute.EARPIECE,
            ),
        )
    }

    @Test
    fun `bluetooth outranks wired and speaker preference only lifts speaker above earpiece`() {
        assertEquals(
            CallAudioRoute.BLUETOOTH,
            CallAudioRoutePolicy.preferred(
                setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.WIRED),
                speakerPreferred = false,
            ),
        )
        assertEquals(
            CallAudioRoute.SPEAKER,
            CallAudioRoutePolicy.preferred(
                setOf(CallAudioRoute.SPEAKER, CallAudioRoute.EARPIECE),
                speakerPreferred = true,
            ),
        )
    }

    @Test
    fun `empty availability falls back to speaker`() {
        assertEquals(
            CallAudioRoute.SPEAKER,
            CallAudioRoutePolicy.preferred(emptySet(), speakerPreferred = false),
        )
    }
}
