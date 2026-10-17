package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.Primary

// ─── InlineKeyboardGrid ───
@Composable
internal fun InlineKeyboardGrid(
    rows: List<List<InlineKeyboardButtonPresentation>>,
    isOwnMessage: Boolean,
    messageId: String,
    onClick: ((String, String) -> Unit)?
) {
    if (rows.isEmpty() || onClick == null) return
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { btn ->
                    val bg = if (isOwnMessage) Color.White.copy(alpha = 0.18f) else Primary.copy(alpha = 0.10f)
                    val fg = if (isOwnMessage) Color.White else Primary
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                val data = btn.callbackData.ifBlank { btn.text }
                                onClick(messageId, data)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btn.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
