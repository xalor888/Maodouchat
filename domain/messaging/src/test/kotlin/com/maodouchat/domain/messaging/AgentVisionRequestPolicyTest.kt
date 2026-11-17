package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentVisionRequestPolicyTest {

    @Test
    fun `visionUrl trims trailing slashes`() {
        assertEquals("https://x/chat/completions", AgentVisionRequestPolicy.visionUrl("https://x/"))
        assertEquals("https://x/chat/completions", AgentVisionRequestPolicy.visionUrl("https://x"))
    }

    @Test
    fun `buildVisionBody carries model system prompt and truncated instruction`() {
        val body = AgentVisionRequestPolicy.buildVisionBody("m1", "i".repeat(2_500), emptyList(), 4)
        assertEquals("m1", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals("只根据图片作答。不要声称已发送或已删除消息。", messages.getJSONObject(0).getString("content"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(1, parts.length())
        assertEquals("i".repeat(2_000), parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `buildVisionBody caps images and defaults mime`() {
        val images = listOf("" to "aaa", "image/png" to "bbb", "image/gif" to "ccc")
        val body = AgentVisionRequestPolicy.buildVisionBody("m1", "look", images, 2)
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals(
            "data:image/jpeg;base64,aaa",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
        assertEquals(
            "data:image/png;base64,bbb",
            parts.getJSONObject(2).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `defaultMime keeps a given mime and falls back to jpeg`() {
        assertEquals("image/png", AgentVisionRequestPolicy.defaultMime("image/png"))
        assertEquals("image/jpeg", AgentVisionRequestPolicy.defaultMime(""))
        assertEquals("image/jpeg", AgentVisionRequestPolicy.defaultMime("   "))
    }

    @Test
    fun `empty images error matches the client`() {
        assertEquals("没有可分析的图片", AgentVisionRequestPolicy.EMPTY_IMAGES_ERROR)
    }

    @Test
    fun `buildVisionBody keeps all images within limit`() {
        val images = listOf("image/png" to "bbb")
        val body = AgentVisionRequestPolicy.buildVisionBody("m1", "look", images, 4)
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(2, parts.length())
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
    }
}
