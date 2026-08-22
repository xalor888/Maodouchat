package com.maodouchat.chatlist

import com.maodouchat.data.repository.ChatListPreviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XAL-20：气泡/列表严格密文判定。宽松 looksLikeWireEnvelope 会把用户 `{hello}` 当密文；
 * isSignalWireEnvelope 必须同时命中 signal- 与 ciphertext/algorithm 等特征。
 */
class SignalWireEnvelopePreviewTest {

    @Test
    fun `signal one to one envelope is wire`() {
        val body = """{"version":2,"algorithm":"signal-v2","ciphertext":"abc"}"""
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope(body))
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope(body))
    }

    @Test
    fun `sender key group envelope is wire`() {
        val body = """{"version":1,"algorithm":"signal-sender-key-v1","groupId":"g1","ciphertext":"xyz"}"""
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope(body))
    }

    @Test
    fun `sender key distribution envelope is wire`() {
        val body =
            """{"version":1,"algorithm":"signal-sender-key-distribution-v1","distributionMessage":"skdm"}"""
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope(body))
    }

    @Test
    fun `user json starting with brace is not a signal envelope`() {
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("""{"hello":"world"}"""))
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope("""{"hello":"world"}"""))
    }

    @Test
    fun `media labels and plaintext are not signal envelopes`() {
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("[图片]"))
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("hello"))
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope(""))
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("signal-v2 without json"))
    }

    @Test
    fun `array envelope without signal marker is not strict wire`() {
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("""[{"deviceId":1}]"""))
        assertTrue(ChatListPreviewPolicy.looksLikeWireEnvelope("""[{"deviceId":1}]"""))
    }
}
