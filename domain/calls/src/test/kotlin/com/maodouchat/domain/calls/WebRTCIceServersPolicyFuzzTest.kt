package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebRTCIceServersPolicyFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomUrl(): String {
        val scheme = listOf(
            "stun:", "turn:", "turns:", "http:", "https:", "ws:",
            "STUN:", "Turn:", "TURNS:", "stun", "",
        ).random(random)
        return scheme + "host.example.com"
    }

    private fun randomServer(): CallIceServer = CallIceServer(
        urls = List(random.nextInt(0, 4)) { randomUrl() },
        username = listOf("", "user", "u-9").random(random),
        credential = listOf("", "pass", "p-9").random(random),
    )

    private fun randomServers(): List<CallIceServer> =
        List(random.nextInt(0, 5)) { randomServer() }

    @Test
    fun `empty config falls back to default stun`() {
        assertEquals(CallIceServer.defaultStun(), resolveIceServers(emptyList()))
        assertEquals(
            listOf(CallIceServer(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))),
            resolveIceServers(emptyList()),
        )
    }

    @Test
    fun `non-empty config returns the same instance`() {
        repeat(200) {
            val configured = randomServers().ifEmpty { listOf(randomServer()) }
            assertSame(configured, resolveIceServers(configured))
            assertEquals(configured, resolveIceServers(configured))
        }
    }

    @Test
    fun `resolved list preserves order urls and credentials`() {
        repeat(200) {
            val configured = randomServers()
            val resolved = resolveIceServers(configured)
            if (configured.isEmpty()) {
                assertEquals(CallIceServer.defaultStun(), resolved)
            } else {
                assertEquals(configured.size, resolved.size)
                configured.zip(resolved).forEach { (expected, actual) ->
                    assertEquals(expected.urls, actual.urls)
                    assertEquals(expected.username, actual.username)
                    assertEquals(expected.credential, actual.credential)
                }
            }
        }
    }

    @Test
    fun `default stun fallback is stable across calls`() {
        repeat(100) {
            assertEquals(CallIceServer.defaultStun(), resolveIceServers(emptyList()))
            assertTrue(CallIceServer.isStunOnly(resolveIceServers(emptyList())))
        }
    }
}
