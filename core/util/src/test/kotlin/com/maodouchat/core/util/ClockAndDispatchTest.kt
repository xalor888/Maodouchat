package com.maodouchat.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G328c：`core/util` 此前**零测试**。
 *
 * 这两条契约看着琐碎，但都是「冻结接口」的存在理由：
 * 领域层不直接读系统时钟、不直接拿 Dispatchers——否则测试无法注入。
 * 所以测的重点是**可注入性本身**，而不是默认实现转发了什么。
 */
class ClockAndDispatchTest {

    @Test
    fun `SystemClock reads wall clock`() {
        val before = System.currentTimeMillis()
        val now = SystemClock.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue("时钟应落在调用前后之间：$before <= $now <= $after", now in before..after)
    }

    @Test
    fun `a fake clock can be injected for determinism`() {
        val fake = Clock { 1_700_000_000_000L }
        assertTrue(fake.nowMillis() == 1_700_000_000_000L)
    }

    @Test
    fun `DefaultDispatcherProvider exposes the three standard dispatchers`() {
        assertSame(Dispatchers.IO, DefaultDispatcherProvider.io)
        assertSame(Dispatchers.Default, DefaultDispatcherProvider.default)
        assertSame(Dispatchers.Main, DefaultDispatcherProvider.main)
    }

    @Test
    fun `a fake dispatcher provider can be injected`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fake = object : DispatcherProvider {
            override val io = testDispatcher
            override val default = testDispatcher
            override val main = testDispatcher
        }
        assertSame(testDispatcher, fake.io)
        assertSame(testDispatcher, fake.main)
    }
}
