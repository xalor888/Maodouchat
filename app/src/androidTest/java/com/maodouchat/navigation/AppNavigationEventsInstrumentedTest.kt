package com.maodouchat.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationEventsInstrumentedTest {

    @Before
    fun clearFlows() {
        runBlocking {
            withTimeoutOrNull(200) { AppNavigationEvents.missedCallsEvents().first() }
                ?.let { AppNavigationEvents.consumeMissedCalls(it) }
            withTimeoutOrNull(200) { AppNavigationEvents.contactsEvents().first() }
                ?.let { AppNavigationEvents.consumeContacts(it) }
        }
    }

    @After
    fun tearDown() {
        clearFlows()
    }

    @Test
    fun missedCallsEventRoundTripsThroughBridge() {
        MaodouchatApp.emitOpenMissedCalls(atMillis = 1234L)

        val req = runBlocking { withTimeoutOrNull(2_000) { AppNavigationEvents.missedCallsEvents().first() } }
        assertNotNull("新事件必须经桥送达", req)
        assertEquals(1234L, req!!.atMillis)
        assertFalse("世代匹配的请求不得被判为过期", AppNavigationEvents.missedCallsIsStale(req))

        AppNavigationEvents.consumeMissedCalls(req)
        assertNull(
            "消费后事件流必须为空",
            runBlocking { withTimeoutOrNull(300) { AppNavigationEvents.missedCallsEvents().first() } },
        )
    }

    @Test
    fun contactsEventRoundTripsThroughBridge() {
        MaodouchatApp.emitOpenContacts(atMillis = 5678L)

        val req = runBlocking { withTimeoutOrNull(2_000) { AppNavigationEvents.contactsEvents().first() } }
        assertNotNull(req)
        assertEquals(5678L, req!!.atMillis)
        assertFalse(AppNavigationEvents.contactsIsStale(req))

        AppNavigationEvents.consumeContacts(req)
        assertNull(
            "消费后事件流必须为空",
            runBlocking { withTimeoutOrNull(300) { AppNavigationEvents.contactsEvents().first() } },
        )
    }

    @Test
    fun staleMissedCallsRequestIsReportedStale() {
        // 自造一个世代必然不匹配的请求（不动全局 generation，避免污染同次运行的其它类）。
        val stale = OpenMissedCallsRequest(
            atMillis = 1L,
            sessionGeneration = MaodouchatApp.currentSessionGeneration() - 1L,
            requestId = -1L,
        )
        assertTrue("世代不符必须判为过期（调用方直接 return）", AppNavigationEvents.missedCallsIsStale(stale))
    }

    @Test
    fun staleContactsRequestIsReportedStale() {
        val stale = OpenContactsRequest(
            atMillis = 1L,
            sessionGeneration = MaodouchatApp.currentSessionGeneration() - 1L,
            requestId = -1L,
        )
        assertTrue(AppNavigationEvents.contactsIsStale(stale))
    }
}
