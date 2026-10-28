package com.maodouchat.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G178：`WebRTCIcePolicy` 的测试（G178 刚从 WebRTCManager 的 3 处同构写法抽出）。
 */
class WebRTCIcePolicyTest {

    @Test
    fun `empty configuration falls back to public stun`() {
        val resolved = resolveIceServers(emptyList())
        assertEquals(CallIceServer.defaultStun(), resolved)
        assertTrue("回落结果应含 google stun", resolved.single().urls.any { it.contains("stun.l.google.com") })
    }

    @Test
    fun `non empty configuration is returned untouched`() {
        val configured = listOf(
            CallIceServer(listOf("turn:turn.example.com:3478"), "user", "pass")
        )
        assertEquals(configured, resolveIceServers(configured))
        // 原样返回且是同一实例——不复制，避免引用被悄悄换掉
        assertSame(configured, resolveIceServers(configured))
    }

    @Test
    fun `the fallback is stun only`() {
        assertTrue(CallIceServer.isStunOnly(resolveIceServers(emptyList())))
    }

    @Test
    fun `a turn configuration is preserved verbatim`() {
        val configured = listOf(
            CallIceServer(listOf("stun:stun.example.com"), "", ""),
            CallIceServer(listOf("turns:turn.example.com:5349"), "u", "c"),
        )
        val resolved = resolveIceServers(configured)
        assertEquals(2, resolved.size)
        assertTrue(CallIceServer.isStunOnly(resolved).not())
        // 凭据不被抹掉
        assertEquals("u", resolved[1].username)
        assertEquals("c", resolved[1].credential)
    }

    @Test
    fun `fallback carries exactly the two google stun urls`() {
        val resolved = resolveIceServers(emptyList())
        assertEquals(1, resolved.size)
        assertEquals(
            listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"),
            resolved.single().urls,
        )
    }

    @Test
    fun `each fallback call builds a fresh list`() {
        assertNotSame(resolveIceServers(emptyList()), resolveIceServers(emptyList()))
        assertEquals(resolveIceServers(emptyList()), resolveIceServers(emptyList()))
    }

    @Test
    fun `single configured server element keeps its identity`() {
        val configured = listOf(CallIceServer(listOf("turn:turn.example.com:3478"), "u", "p"))
        assertSame(configured.single(), resolveIceServers(configured).single())
    }

    @Test
    fun `uppercase turn and turns urls still count as turn`() {
        assertFalse(
            CallIceServer.isStunOnly(
                listOf(CallIceServer(listOf("TURN:turn.example.com:3478"), "u", "p"))
            )
        )
        assertFalse(
            CallIceServer.isStunOnly(
                listOf(CallIceServer(listOf("TURNS:turn.example.com:5349"), "u", "p"))
            )
        )
    }

    @Test
    fun `turn lookalike prefix does not count as turn`() {
        assertTrue(
            CallIceServer.isStunOnly(
                listOf(CallIceServer(listOf("turnx:turn.example.com:3478"), "u", "p"))
            )
        )
        assertTrue(CallIceServer.isStunOnly(emptyList()))
    }
    @Test
    fun `degenerate non-empty configuration is not replaced by fallback`() {
        // ifEmpty 只看列表是否为空：哪怕服务器本身没有 url，也不回落
        val configured = listOf(CallIceServer(emptyList(), "u", "p"))
        val resolved = resolveIceServers(configured)
        assertSame(configured, resolved)
        assertEquals(1, resolved.size)
        assertTrue(resolved.single().urls.isEmpty())
        assertEquals("u", resolved.single().username)
    }

    @Test
    fun `fallback stun server carries no credentials`() {
        val server = resolveIceServers(emptyList()).single()
        assertEquals("", server.username)
        assertEquals("", server.credential)
    }

    @Test
    fun `configured server order is preserved across stun turn and turns`() {
        val configured = listOf(
            CallIceServer(listOf("stun:stun.example.com"), "", ""),
            CallIceServer(listOf("turn:turn.example.com:3478"), "u", "p"),
            CallIceServer(listOf("turns:turn.example.com:5349"), "u2", "p2"),
        )
        val resolved = resolveIceServers(configured)
        assertEquals(
            listOf(
                "stun:stun.example.com",
                "turn:turn.example.com:3478",
                "turns:turn.example.com:5349",
            ),
            resolved.flatMap { it.urls },
        )
        assertFalse(CallIceServer.isStunOnly(resolved))
    }

    @Test
    fun `fallback server element is a fresh instance on each call`() {
        assertNotSame(
            resolveIceServers(emptyList()).single(),
            resolveIceServers(emptyList()).single(),
        )
        assertEquals(
            resolveIceServers(emptyList()).single(),
            resolveIceServers(emptyList()).single(),
        )
    }

    @Test
    fun `only turn and turns schemes disqualify stun only`() {
        assertTrue(
            CallIceServer.isStunOnly(
                listOf(
                    CallIceServer(listOf("stuns:stun.example.com:443"), "", ""),
                    CallIceServer(listOf("turnserver.example.com"), "", ""),
                )
            )
        )
    }

}
