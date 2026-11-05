package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkErrorModelsTest {

    @Test
    fun `api failure kinds keep declaration order and valueOf round trip`() {
        val kinds = ApiFailureKind.entries.toList()
        assertEquals(5, kinds.size)
        assertEquals(ApiFailureKind.HTTP, kinds.first())
        assertEquals(
            ApiFailureKind.UNEXPECTED,
            ApiFailureKind.valueOf("UNEXPECTED")
        )
    }

    @Test
    fun `websocket error kinds keep declaration order and valueOf round trip`() {
        val kinds = WebSocketErrorKind.entries.toList()
        assertEquals(11, kinds.size)
        assertEquals(WebSocketErrorKind.CONNECTION, kinds.first())
        assertEquals(
            WebSocketErrorKind.FRIEND_REQUEST_PARSE,
            WebSocketErrorKind.valueOf("FRIEND_REQUEST_PARSE")
        )
    }
}
