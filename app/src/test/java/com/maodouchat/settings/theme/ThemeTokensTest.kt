package com.maodouchat.settings.theme

import com.maodouchat.core.model.ThemeFamily
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
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize("tg_classic"))
        assertEquals(ThemeFamily.TG_CLASSIC, ThemeFamily.normalize("telegram"))
        assertEquals(ThemeFamily.TG_MIDNIGHT, ThemeFamily.normalize("midnight"))
        assertEquals(ThemeFamily.TG_GRAPHITE, ThemeFamily.normalize("graphite"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize("unknown_random"))
        assertEquals(ThemeFamily.MAODOU, ThemeFamily.normalize(null))
    }
}
