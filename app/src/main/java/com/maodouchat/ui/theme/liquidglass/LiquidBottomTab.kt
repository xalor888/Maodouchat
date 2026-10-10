package com.maodouchat.ui.theme.liquidglass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowScope.LiquidBottomTab(
    selected: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    interactive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scale = LocalLiquidNavigationScale.current
    Column(
        Modifier
            .then(if (interactive) Modifier.clip(Capsule()) else Modifier)
            .then(
                if (interactive) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onLongClickLabel = onLongClickLabel,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (interactive) {
                    Modifier.semantics {
                        this.selected = selected
                        role = Role.Tab
                    }
                } else {
                    Modifier
                },
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
