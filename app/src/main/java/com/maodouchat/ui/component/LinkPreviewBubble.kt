package com.maodouchat.ui.component

import android.annotation.SuppressLint
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.maodouchat.util.LinkPreviewPreferences
import com.maodouchat.util.LinkPreviewRepository
import com.maodouchat.util.RuntimeFlags

// ─── LinkPreviewSlot ───
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun LinkPreviewSlot(
    messageContent: String,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier,
    secretChat: Boolean = false
) {
    val context = LocalContext.current
    val userEnabled = remember(LinkPreviewPreferences.version) { LinkPreviewPreferences.isEnabled(context) }
    val secretBlocksPreview = secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_LINK_PREVIEW_BLOCK)
    val enabled = userEnabled && !secretBlocksPreview
    val url = remember(messageContent, enabled) {
        if (!enabled) null else LinkPreviewPolicy.firstHttpUrl(messageContent)
    }
    if (url == null) return

    var preview by remember(url) {
        mutableStateOf(LinkPreviewRepository.cached(url))
    }

    LaunchedEffect(url) {
        // fetch 自带正/负缓存与 in-flight 去重；失败返回 null
        preview = LinkPreviewRepository.fetch(url)
    }

    val card = preview ?: return
    if (!LinkPreviewPolicy.isUseful(card)) return

    LinkPreviewCard(
        preview = card,
        isOwnMessage = isOwnMessage,
        modifier = modifier,
        onOpen = {
            if (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.maodouchat.R.string.secret_external_link_blocked),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@LinkPreviewCard
            }
            com.maodouchat.navigation.AppLinkOpener.openUserFacingUrl(context, card.url)
        }
    )
}


// ─── LinkPreviewCard ───
@Composable
internal fun LinkPreviewCard(
    preview: LinkPreviewPolicy.Preview,
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
