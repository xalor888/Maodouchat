package com.maodouchat.ui.component

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import com.maodouchat.navigation.AppLinkOpener
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
                    context.getString(R.string.secret_external_link_blocked),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@LinkPreviewCard
            }
            AppLinkOpener.openUserFacingUrl(context, card.url)
        }
    )
}
