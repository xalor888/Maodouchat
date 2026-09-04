package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B13：typed runtime settings registry 的类型、范围与校验行为。
 */
class RuntimeSettingsRegistryTest {

    @Test
    fun `registry has unique keys and covers defaults`() {
        val specs = RuntimeSettingsRegistry.specs
        val keys = specs.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate keys present")
        assertEquals(RuntimeConfigService.defaults().keys, keys.toSet())
        assertEquals(specs.size, RuntimeSettingsRegistry.byKey.size)
    }

    @Test
    fun `numeric specs carry coerce ranges matching getters`() {
        val maxGroup = RuntimeSettingsRegistry.spec(RuntimeConfigService.KEY_MAX_GROUP_SIZE)!!
        assertEquals(RuntimeSettingType.INT, maxGroup.type)
        assertEquals(2L, maxGroup.min)
        assertEquals(5000L, maxGroup.max)

        val maxMsg = RuntimeSettingsRegistry.spec(RuntimeConfigService.KEY_MAX_MESSAGE_PER_MIN)!!
        assertEquals(10L, maxMsg.min)
        assertEquals(600L, maxMsg.max)

        val maxBots = RuntimeSettingsRegistry.spec(RuntimeConfigService.KEY_MAX_BOTS_PER_USER)!!
        assertEquals(1L, maxBots.min)
        assertEquals(200L, maxBots.max)

        val budget = RuntimeSettingsRegistry.spec(RuntimeConfigService.KEY_AI_DAILY_TOKEN_BUDGET_PER_USER)!!
        assertEquals(RuntimeSettingType.LONG, budget.type)
        assertEquals(0L, budget.min)
        assertEquals(1_000_000_000L, budget.max)
    }

    @Test
    fun `unknown key is not specd`() {
        assertNull(RuntimeSettingsRegistry.spec("does_not_exist"))
    }

    @Test
    fun `normalize canonicalizes booleans and rejects junk`() {
        assertEquals("true", RuntimeConfigService.normalize(RuntimeConfigService.KEY_AI_ENABLED, "1"))
        assertEquals("true", RuntimeConfigService.normalize(RuntimeConfigService.KEY_AI_ENABLED, "ON"))
        assertEquals("false", RuntimeConfigService.normalize(RuntimeConfigService.KEY_AI_ENABLED, "off"))
        assertNull(RuntimeConfigService.normalize(RuntimeConfigService.KEY_AI_ENABLED, "maybe"))
        assertNull(RuntimeConfigService.normalize("unknown_key", "true"))
    }

    @Test
    fun `normalize enforces numeric ranges`() {
        assertTrue(RuntimeConfigService.normalize(RuntimeConfigService.KEY_MAX_GROUP_SIZE, "250") != null)
        assertNull(RuntimeConfigService.normalize(RuntimeConfigService.KEY_MAX_GROUP_SIZE, "1"))
        assertNull(RuntimeConfigService.normalize(RuntimeConfigService.KEY_MAX_GROUP_SIZE, "999999"))
        assertNull(RuntimeConfigService.normalize(RuntimeConfigService.KEY_MAX_GROUP_SIZE, "abc"))
    }

    @Test
    fun `normalize passes strings through`() {
        assertNotNull(RuntimeConfigService.normalize(RuntimeConfigService.KEY_GLOBAL_BANNER, "hi"))
        assertEquals("hi", RuntimeConfigService.normalize(RuntimeConfigService.KEY_GLOBAL_BANNER, " hi "))
    }
}
