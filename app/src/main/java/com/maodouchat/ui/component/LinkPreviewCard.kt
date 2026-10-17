package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.util.LinkPreviewPolicy
import com.maodouchat.util.Preview

// ─── LinkPreviewCard ───
@Composable
internal fun LinkPreviewCard(
    preview: Preview,
    isOwnMessage: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalChatPalette.current
    val bg = if (isOwnMessage) {
        LocalChatBubbleColor.current.copy(alpha = 0.55f)
    } else {
        palette.chatInputBackground
    }
    val titleColor = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    val descColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextSecondary
    val hostColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
    val site = preview.siteName?.takeIf { it.isNotBlank() }
        ?: LinkPreviewPolicy.displayHost(preview.url)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onOpen)
            .padding(bottom = 8.dp)
    ) {
        if (!preview.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = LocalContext.current,
                    data = preview.imageUrl,
                    sizeWidth = 640,
                    sizeHeight = 360,
                ),
                contentDescription = stringResource(R.string.message_link_preview_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = site,
                style = MaterialTheme.typography.labelSmall,
                color = hostColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!preview.title.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!preview.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = descColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
