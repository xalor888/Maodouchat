package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalChatPalette

/** Centered timeline event renderer with no domain or transport dependencies. */
@Composable
internal fun SystemMessageRenderer(
    content: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChatPalette.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.systemMessageText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(palette.systemMessageBackground, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
