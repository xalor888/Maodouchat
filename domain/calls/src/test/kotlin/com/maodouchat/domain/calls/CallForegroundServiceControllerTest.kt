package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallForegroundServiceControllerTest {

    private class Fixture {
        var state = CallUiState(contactName = "Ada", callType = CallType.VIDEO)
        var callId = "call-9"
        val starts = mutableListOf<Triple<String, Boolean, String>>()
        var stops = 0

        fun controller() = CallForegroundServiceController(
            startCallForeground = { name, video, id -> starts += Triple(name, video, id) },
            stopCallForeground = { stops++ },
            currentState = { state },
            activeCallId = { callId },
            unknownCallerLabel = "Unknown caller",
        )
    }

    @Test
    fun startUsesContactNameAndVideoFlag() {
        val f = Fixture()
        f.controller().start()
        assertEquals(listOf(Triple("Ada", true, "call-9")), f.starts)
    }

    @Test
    fun startFallsBackToUnknownLabelWhenNameBlank() {
        val f = Fixture()
        f.state = f.state.copy(contactName = "  ", callType = CallType.AUDIO)
        f.controller().start()
        assertEquals(listOf(Triple("Unknown caller", false, "call-9")), f.starts)
    }

    @Test
    fun stopCallsPort() {
        val f = Fixture()
        val c = f.controller()
        c.stop()
        c.stop()
        assertEquals(2, f.stops)
        assertTrue(f.starts.isEmpty())
    }
}
