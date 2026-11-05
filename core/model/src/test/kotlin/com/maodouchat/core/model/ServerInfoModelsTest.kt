package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInfoModelsTest {

    @Test
    fun `server info keeps brand fields and base url`() {
        val info = ServerInfo("节点", "简介", "公告", "https://example.invalid",
            "1.0", true, "https://example.invalid")
        assertEquals("节点", info.name)
        assertEquals("https://example.invalid", info.baseUrl)
        assertTrue(info.registrationOpen)
    }
}
