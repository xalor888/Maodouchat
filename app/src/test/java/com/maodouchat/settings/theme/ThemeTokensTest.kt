package com.maodouchat.settings.theme

import com.maodouchat.ui.theme.tokens.MaodouDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {

    @Test
    fun `MaodouDesignTokens provides coherent spacing hierarchy`() {
        assertTrue(MaodouDesignTokens.Spacing.TinyGap < MaodouDesignTokens.Spacing.SmallGap)
        assertTrue(MaodouDesignTokens.Spacing.SmallGap < MaodouDesignTokens.Spacing.ItemGap)
        assertTrue(MaodouDesignTokens.Spacing.ItemGap < MaodouDesignTokens.Spacing.ScreenPadding)
        assertTrue(MaodouDesignTokens.Spacing.ScreenPadding <= MaodouDesignTokens.Spacing.SectionGap)
    }

    @Test
    fun `MaodouDesignTokens provides non-null standard shapes and semantics`() {
        assertNotNull(MaodouDesignTokens.Shapes.Card)
        assertNotNull(MaodouDesignTokens.Shapes.Control)
        assertNotNull(MaodouDesignTokens.Shapes.Small)
        assertNotNull(MaodouDesignTokens.Shapes.Large)
        assertNotNull(MaodouDesignTokens.Shapes.Full)

        assertNotNull(MaodouDesignTokens.Semantics.PrimaryColor)
        assertNotNull(MaodouDesignTokens.Semantics.BackgroundColor)
        assertNotNull(MaodouDesignTokens.Semantics.SurfaceColor)
        assertNotNull(MaodouDesignTokens.Semantics.PrimaryText)
        assertNotNull(MaodouDesignTokens.Semantics.SecondaryText)
    }

    @Test
    fun `ThemeFamily normalization correctly maps Telegram variants and falls back to Maodou`() {
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.TG_CLASSIC, com.maodouchat.ui.theme.ThemeFamily.normalize("tg_classic"))
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.TG_CLASSIC, com.maodouchat.ui.theme.ThemeFamily.normalize("telegram"))
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.TG_MIDNIGHT, com.maodouchat.ui.theme.ThemeFamily.normalize("midnight"))
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.TG_GRAPHITE, com.maodouchat.ui.theme.ThemeFamily.normalize("graphite"))
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.MAODOU, com.maodouchat.ui.theme.ThemeFamily.normalize("unknown_random"))
        assertEquals(com.maodouchat.ui.theme.ThemeFamily.MAODOU, com.maodouchat.ui.theme.ThemeFamily.normalize(null))
    }
}
