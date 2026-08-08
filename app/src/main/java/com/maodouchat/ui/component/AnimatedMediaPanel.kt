package com.maodouchat.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.SurfaceVariant

enum class MediaPanelTab(@StringRes val labelRes: Int) {
    EMOJI(R.string.media_panel_tab_emoji),
    STICKER(R.string.media_panel_tab_sticker),
    GIF(R.string.media_panel_tab_gif)
}

/**
 * Animated media panel with smooth tab switching and item animations.
 */
@Composable
fun AnimatedMediaPanel(
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit = {},
    onStickerSelected: (String) -> Unit = {},
    onGifSelected: (String) -> Unit = {}
) {
    val motion = LocalMotionSettings.current
    var selectedTab by remember { mutableStateOf(MediaPanelTab.EMOJI) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Tab bar with animated indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MediaPanelTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = 400f
                    ),
                    label = "tabScale${tab.name}"
                )

                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable { selectedTab = tab }
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Animated content switcher
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                (slideInVertically { it / 4 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 4 } + fadeOut())
            },
            label = "mediaPanelContent"
        ) { tab ->
            when (tab) {
                MediaPanelTab.EMOJI -> {
                    EmojiGrid(onEmojiSelected = onEmojiSelected)
                }
                MediaPanelTab.STICKER -> {
                    StickerGrid(onStickerSelected = onStickerSelected)
                }
                MediaPanelTab.GIF -> {
                    GifGrid(onGifSelected = onGifSelected)
                }
            }
        }
    }
}

@Composable
private fun EmojiGrid(onEmojiSelected: (String) -> Unit) {
    val emojis = remember {
        listOf(
            "\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDE21", "\uD83D\uDE22",
            "\uD83D\uDE31", "\uD83D\uDE0F", "\uD83D\uDE0A", "\uD83D\uDE07", "\uD83D\uDE0E",
            "\uD83D\uDC4D", "\uD83D\uDC4E", "\u2764\uFE0F", "\uD83D\uDC94", "\uD83D\uDC95",
            "\uD83D\uDC4F", "\uD83D\uDE4F", "\uD83D\uDCAA", "\uD83C\uDF89", "\uD83C\uDF81"
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .height(200.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(emojis) { emoji ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEmojiSelected(emoji) }
            ) {
                Text(
                    text = emoji,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        // Bouncy scale on item
                    }
                )
            }
        }
    }
}

@Composable
private fun StickerGrid(onStickerSelected: (String) -> Unit) {
    // Placeholder for sticker grid
    Box(
        modifier = Modifier
            .height(200.dp)
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.media_panel_sticker_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GifGrid(onGifSelected: (String) -> Unit) {
    // Placeholder for GIF grid
    Box(
        modifier = Modifier
            .height(200.dp)
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.media_panel_gif_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
