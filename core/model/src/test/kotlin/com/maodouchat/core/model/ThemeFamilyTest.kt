package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFamilyTest {

    @Test
    fun `theme families keep declaration order and ids`() {
        assertEquals(
            listOf("maodou", "tg_classic", "tg_midnight", "tg_graphite"),
            ThemeFamily.entries.map { it.id },
        )
        assertEquals(ThemeFamily.entries, ThemeFamily.ALL)
        assertEquals(listOf(ThemeFamily.MAODOU), ThemeFamily.PICKABLE)
    }

    @Test
    fun `normalize maps telegram variants and falls back to maodou`() {
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize("tg_classic"))
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize("telegram"))
        assertEquals(ThemeFamily.TG_MIDNIGHT, ThemeFamily.normalize("midnight"))
        assertEquals(ThemeFamily.TG_GRAPHITE, ThemeFamily.normalize("graphite"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("unknown_random"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize(null))
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize(" TG_Classic "))
    }

    @Test
    fun `liquid glass and telegram flags match the family`() {
        assertTrue(ThemeFamily.MAODOU.isLiquidGlass)
        assertTrue(ThemeFamily.TG_CLASSIC.isTelegram)
        assertTrue(ThemeFamily.TG_MIDNIGHT.isTelegram)
        assertTrue(ThemeFamily.TG_GRAPHITE.isTelegram)
        assertEquals(false, ThemeFamily.MAODOU.isTelegram)
    }
}
