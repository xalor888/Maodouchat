package com.maodouchat.ui.theme.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextPrimary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

/**
 * Unified semantic design tokens across the Maodouchat application.
 * Prevents ad-hoc hardcoded colors, gaps, and corner radii throughout settings & screens.
 */
object MaodouDesignTokens {

    object Spacing {
        val ScreenPadding: Dp = MaodouDimens.ScreenPadding
        val SectionGap: Dp = MaodouDimens.SectionGap
        val ItemGap: Dp = MaodouDimens.ItemGap
        val SmallGap: Dp = MaodouDimens.SmallGap
        val TinyGap: Dp = MaodouDimens.TinyGap
    }

    object Shapes {
        val Card: Shape = RoundedCornerShape(MaodouDimens.CardRadius)
        val Control: Shape = RoundedCornerShape(MaodouDimens.ControlRadius)
        val Small: Shape = RoundedCornerShape(MaodouDimens.SmallRadius)
        val Large: Shape = RoundedCornerShape(20.dp)
        val Full: Shape = RoundedCornerShape(percent = 50)
    }

    object Elevation {
        val None: Dp = 0.dp
        val Low: Dp = 2.dp
        val Medium: Dp = 6.dp
        val High: Dp = 12.dp
    }

    object Semantics {
        val PrimaryColor: Color = Primary
        val BackgroundColor: Color = Background
        val SurfaceColor: Color = Surface
        val PrimaryText: Color = TextPrimary
        val SecondaryText: Color = TextSecondary
        val ErrorColor: Color = Error
        val SuccessColor: Color = OnlineGreen
        val BadgeColor: Color = UnreadRed
    }
}
