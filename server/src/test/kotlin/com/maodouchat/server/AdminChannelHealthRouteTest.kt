package com.maodouchat.server

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AdminChannelHealthResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminChannelHealthRouteTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `maskHost never returns raw host`() {
        assertEquals("", ServerConfig.maskHost(""))
        assertEquals("••••", ServerConfig.maskHost("ab"))
        val masked = ServerConfig.maskHost("smtp.163.com")
        assertTrue(masked.contains("••••"), masked)
        assertFalse(masked.contains("163.com"), masked)
        assertFalse(masked.contains("smtp.163"), masked)
    }

    @Test
    fun `channel health json has booleans and masks but no secret keys`() {
        val body = json.encodeToString(
            AdminChannelHealthResponse(
                openaiConfigured = true,
                turnConfigured = true,
                smtpConfigured = true,
                jwtConfigured = true,
                openaiModel = "deepseek-ai/DeepSeek-V3.2",
                turnUrlCount = 2,
                smtpHostMasked = ServerConfig.maskHost("smtp.163.com")
            )
        )
        assertTrue(body.contains("\"openaiConfigured\":true"), body)
        assertTrue(body.contains("\"turnUrlCount\":2"), body)
        assertFalse(body.contains("OPENAI_API_KEY"), body)
        assertFalse(body.contains("sk-"), body)
        assertFalse(body.contains("TURN_SHARED_SECRET"), body)
        assertFalse(body.contains("JWT_SECRET"), body)
        assertFalse(body.contains("smtp.163.com"), body)
    }
}
