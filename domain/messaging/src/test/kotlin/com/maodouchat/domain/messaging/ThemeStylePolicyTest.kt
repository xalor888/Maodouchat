package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeStylePolicyTest {

    @Test
    fun nullReturnsMaodou() {
        assertEquals("maodou", ThemeStylePolicy.normalizeThemeStyle(null))
    }

    @Test
    fun tgAliasesMapToClassic() {
        assertEquals("tg_classic", ThemeStylePolicy.normalizeThemeStyle("tg"))
        assertEquals("tg_classic", ThemeStylePolicy.normalizeThemeStyle("telegram"))
        assertEquals("tg_classic", ThemeStylePolicy.normalizeThemeStyle("telegram_classic"))
    }

    @Test
    fun midnightAlias() {
        assertEquals("tg_midnight", ThemeStylePolicy.normalizeThemeStyle("midnight"))
    }

    @Test
    fun graphiteAlias() {
        assertEquals("tg_graphite", ThemeStylePolicy.normalizeThemeStyle("graphite"))
    }

    @Test
    fun unknownReturnsMaodou() {
        assertEquals("maodou", ThemeStylePolicy.normalizeThemeStyle("material"))
    }

    @Test
    fun styleIsCaseInsensitiveAndTrimmed() {
        assertEquals("tg_midnight", ThemeStylePolicy.normalizeThemeStyle(" TG_MIDNIGHT "))
    }
}
