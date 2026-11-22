package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupCallCapabilitiesFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomUrl(): String {
        val scheme = listOf(
            "stun:", "turn:", "turns:", "http:", "https:", "ws:",
            "STUN:", "Turn:", "TURNS:", "TURN:", "stun", "",
        ).random(random)
        return scheme + "host.example.com"
    }

    // isStunOnly 的逐字复述：空表算纯 STUN；只要任一 URL 以 turn:/turns:
    // 开头（大小写不敏感）即非纯 STUN。
    private fun oracleIsStunOnly(servers: List<CallIceServer>): Boolean {
        if (servers.isEmpty()) return true
        return servers.none { server ->
            server.urls.any { url ->
                url.startsWith("turn:", ignoreCase = true) ||
                    url.startsWith("turns:", ignoreCase = true)
            }
        }
    }

    @Test
    fun `canStartMesh matches the range oracle over random counts`() {
        val max = GroupCallCapabilities.MAX_MESH_MEMBERS
        repeat(500) {
            val count = when (random.nextInt(8)) {
                0 -> 0
                1 -> 1
                2 -> 2
                3 -> max
                4 -> max + 1
                5 -> Int.MAX_VALUE
                6 -> Int.MIN_VALUE
                else -> random.nextInt(-10, 20)
            }
            assertEquals(
                count in 2..max,
                GroupCallCapabilities.canStartMesh(count),
                "count=$count max=$max",
            )
        }
    }

    @Test
    fun `max mesh members aligns with the group call policy`() {
        assertEquals(GroupCallPolicy.MAX_MESH_MEMBERS, GroupCallCapabilities.MAX_MESH_MEMBERS)
        assertEquals(6, GroupCallCapabilities.MAX_MESH_MEMBERS)
        assertTrue(GroupCallCapabilities.canStartMesh(2))
        assertTrue(GroupCallCapabilities.canStartMesh(6))
        assertEquals(false, GroupCallCapabilities.canStartMesh(1))
        assertEquals(false, GroupCallCapabilities.canStartMesh(7))
    }

    @Test
    fun `every feature is unimplemented`() {
        for (feature in Feature.entries) {
            assertEquals(true, GroupCallCapabilities.isUnimplemented(feature), "feature=$feature")
        }
        assertEquals(false, GroupCallCapabilities.SFU_SUPPORTED)
        assertEquals(false, GroupCallCapabilities.SCREEN_SHARE_SUPPORTED)
        assertEquals(false, GroupCallCapabilities.CALL_RECORDING_SUPPORTED)
    }

    @Test
    fun `default stun servers are stun only`() {
        val servers = CallIceServer.defaultStun()
        assertEquals(1, servers.size)
        assertEquals(
            listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"),
            servers.first().urls,
        )
        assertEquals("", servers.first().username)
        assertEquals("", servers.first().credential)
        assertTrue(CallIceServer.isStunOnly(servers))
    }

    @Test
    fun `isStunOnly matches the oracle over random server lists`() {
        repeat(300) {
            val servers = List(random.nextInt(0, 4)) {
                CallIceServer(
                    urls = List(random.nextInt(0, 4)) { randomUrl() },
                    username = "u",
                    credential = "p",
                )
            }
            assertEquals(
                oracleIsStunOnly(servers),
                CallIceServer.isStunOnly(servers),
                "servers=$servers",
            )
        }
    }

    @Test
    fun `call ice server preserves fields and defaults`() {
        repeat(100) {
            val urls = List(random.nextInt(0, 4)) { randomUrl() }
            val server = CallIceServer(urls)
            assertEquals(urls, server.urls)
            assertEquals("", server.username)
            assertEquals("", server.credential)
            assertEquals(server, server.copy())
        }
    }
}
