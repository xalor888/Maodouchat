package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerChangeResultModelsTest {

    @Test
    fun `server change results cover changed unchanged and failed`() {
        assertEquals(ServerChangeResult.Changed, ServerChangeResult.Changed)
        assertNotEquals(ServerChangeResult.Changed, ServerChangeResult.Unchanged)
        assertEquals("bad url", (ServerChangeResult.Failed("bad url") as ServerChangeResult.Failed).message)
    }
}
