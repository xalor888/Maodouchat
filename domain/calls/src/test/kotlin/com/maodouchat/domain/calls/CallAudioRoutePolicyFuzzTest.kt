package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallAudioRoutePolicyFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomAvailable(): Set<CallAudioRoute> {
        val shuffled = CallAudioRoute.entries.shuffled(random)
        return shuffled.take(random.nextInt(0, 5)).toSet()
    }

    private fun randomManualRoute(): CallAudioRoute? =
        if (random.nextInt(3) == 0) null else CallAudioRoute.entries.random(random)

    // candidates 三段构造的逐字复述：手动路由优先（须在可用集内）；随后按
    // 蓝牙/有线/扬声器（仅偏好时）/听筒/扬声器顺序去重追加；最后补齐可用集
    // 剩余路由；空集兜底扬声器。
    private fun oracleCandidates(
        available: Set<CallAudioRoute>,
        speakerPreferred: Boolean,
        manualRoute: CallAudioRoute?,
    ): List<CallAudioRoute> {
        val out = mutableListOf<CallAudioRoute>()
        if (manualRoute != null && manualRoute in available) out.add(manualRoute)
        val ordered = listOfNotNull(
            CallAudioRoute.BLUETOOTH.takeIf { it in available },
            CallAudioRoute.WIRED.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { speakerPreferred && it in available },
            CallAudioRoute.EARPIECE.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { it in available },
        )
        for (route in ordered) if (route !in out) out.add(route)
        for (route in available) if (route !in out) out.add(route)
        if (out.isEmpty()) out.add(CallAudioRoute.SPEAKER)
        return out
    }

    @Test
    fun `candidates matches verbatim oracle over random availability`() {
        repeat(300) {
            val available = randomAvailable()
            val speakerPreferred = random.nextBoolean()
            val manualRoute = randomManualRoute()
            assertEquals(
                oracleCandidates(available, speakerPreferred, manualRoute),
                CallAudioRoutePolicy.candidates(available, speakerPreferred, manualRoute),
                "available=$available speakerPreferred=$speakerPreferred manualRoute=$manualRoute",
            )
        }
    }

    @Test
    fun `preferred and selected return the candidates head`() {
        repeat(200) {
            val available = randomAvailable()
            val speakerPreferred = random.nextBoolean()
            val manualRoute = randomManualRoute()
            val autoHead = CallAudioRoutePolicy.candidates(available, speakerPreferred, null).first()
            val manualHead =
                CallAudioRoutePolicy.candidates(available, speakerPreferred, manualRoute).first()
            // preferred 不带手动路由，selected 才吃 manualRoute。
            assertEquals(autoHead, CallAudioRoutePolicy.preferred(available, speakerPreferred))
            assertEquals(
                manualHead,
                CallAudioRoutePolicy.selected(available, speakerPreferred, manualRoute),
            )
        }
    }

    @Test
    fun `candidates never duplicates and covers the available set`() {
        repeat(200) {
            val available = randomAvailable()
            val candidates = CallAudioRoutePolicy.candidates(
                available,
                random.nextBoolean(),
                randomManualRoute(),
            )
            assertEquals(candidates.size, candidates.toSet().size, "available=$available")
            if (available.isEmpty()) {
                assertEquals(listOf(CallAudioRoute.SPEAKER), candidates)
            } else {
                assertTrue(candidates.containsAll(available), "available=$available")
            }
        }
    }

    @Test
    fun `manual route in the available set always leads`() {
        repeat(200) {
            val available = randomAvailable()
            val manualRoute = CallAudioRoute.entries.random(random)
            if (manualRoute !in available) return@repeat
            val candidates = CallAudioRoutePolicy.candidates(
                available,
                random.nextBoolean(),
                manualRoute,
            )
            assertEquals(manualRoute, candidates.first(), "available=$available")
        }
    }

    @Test
    fun `speaker preference only promotes speaker ahead of earpiece`() {
        repeat(200) {
            val available = setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER)
            val speakerPreferred = random.nextBoolean()
            val head = CallAudioRoutePolicy.preferred(available, speakerPreferred)
            assertEquals(
                if (speakerPreferred) CallAudioRoute.SPEAKER else CallAudioRoute.EARPIECE,
                head,
                "speakerPreferred=$speakerPreferred",
            )
        }
    }

    @Test
    fun `callAudioRouteKind maps every route by name`() {
        for (route in CallAudioRoute.entries) {
            val kind = callAudioRouteKind(route)
            assertEquals(route.name, kind.name, "route=$route")
            assertEquals(route, CallAudioRoute.valueOf(kind.name))
        }
        assertEquals(4, CallAudioRouteKind.entries.size)
        assertEquals(
            CallAudioRoute.entries.map { it.name }.toSet(),
            CallAudioRouteKind.entries.map { it.name }.toSet(),
        )
    }
}
