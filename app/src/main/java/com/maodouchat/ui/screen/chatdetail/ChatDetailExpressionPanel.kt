package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.launch


/**
 * 输入框的表情/贴纸/GIF 面板（G107 从 `ChatDetailComponents.kt` 拆出，原 363 行）。
 *
 * 三种模式共用一个面板：最近表情 + 表情网格、贴纸包（含包管理/搜索/远程按需下载）、GIF 库。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * - **贴纸页签默认落在「最近使用」包**，且「最近使用」行只在无搜索词时展示；
 * - 点击表情上屏的同时记录最近使用（去重、按账号隔离）；
 * - 贴纸按压回弹（9.223，TG 式手感，尊重系统动效开关）；
 * - 远程贴纸包按需拉取清单 + 下载（B1 包体瘦身闭环）；
 * - 贴纸入口整体受 `stickersEnabled` 控制（群/频道禁言场景）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

@Composable
internal fun ExpressionPanel(
    mode: String,
    onModeChange: (String) -> Unit,
    onEmojiClick: (String) -> Unit,
    onStickerClick: (String) -> Unit,
    onGifClick: () -> Unit,
    stickersEnabled: Boolean
) {
    val context = LocalContext.current
    var stickerTab by rememberSaveable { mutableStateOf(com.maodouchat.util.StickerCatalog.PACK_RECENT) }
    var stickerQuery by rememberSaveable { mutableStateOf("") }
    var showPackManager by rememberSaveable { mutableStateOf(false) }
    var enabledPackIds by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getEnabledPackIds(context))
    }
    var recentStickers by remember {
        mutableStateOf(com.maodouchat.util.StickerPreferences.getRecent(context))
    }
    var recentEmojis by remember {
        mutableStateOf(com.maodouchat.util.EmojiRecentPreferences.getRecent(context))
    }
    val enabledPacks = remember(enabledPackIds) {
        com.maodouchat.util.StickerPolicy.enabledPacks(enabledPackIds)
    }
    val searchHits = remember(stickerQuery, enabledPackIds) {
        com.maodouchat.util.StickerPolicy.searchStickers(stickerQuery, enabledPacks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mode == "STICKER") 280.dp else 224.dp)
            .background(LocalChatPalette.current.chatInputBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EMOJI" to stringResource(R.string.chat_emoji), "STICKER" to stringResource(R.string.chat_sticker)).forEach { (key, label) ->
                    val selected = mode == key
                    TextButton(
                        onClick = { onModeChange(key) },
                        modifier = Modifier.background(
                            if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                    ) { Text(label, color = if (selected) Primary else TextSecondary) }
                }
            }
            if (mode == "STICKER") {
                IconButton(onClick = { showPackManager = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.sticker_pack_manage), tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onGifClick, enabled = stickersEnabled) {
                Icon(Icons.Outlined.GifBox, contentDescription = stringResource(R.string.chat_select_gif), tint = if (stickersEnabled) Primary else TextHint)
            }
        }

        if (mode == "STICKER") {
            OutlinedTextField(
                value = stickerQuery,
                onValueChange = { stickerQuery = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sticker_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (stickerQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StickerPackChip(
                        label = stringResource(R.string.sticker_pack_recent),
                        selected = stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT,
                        onClick = { stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT }
                    )
                    enabledPacks.forEach { pack ->
                        StickerPackChip(
                            label = stickerPackLabel(pack.nameKey),
                            selected = stickerTab == pack.id,
                            onClick = { stickerTab = pack.id }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            val displayItems = when {
                stickerQuery.isNotBlank() -> searchHits
                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> recentStickers
                else -> enabledPacks.firstOrNull { it.id == stickerTab }?.stickers
                    ?: enabledPacks.firstOrNull()?.stickers
                    ?: emptyList()
            }
            if (displayItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            when {
                                stickerQuery.isNotBlank() -> R.string.sticker_search_empty
                                stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> R.string.sticker_recent_empty
                                else -> R.string.sticker_search_empty
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    displayItems.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                // 9.223：贴纸按压回弹（TG 式手感，尊重系统动效开关）
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 32.sp,
                                    enabled = stickersEnabled,
                                    onClick = {
                                        onStickerClick(item)
                                        recentStickers = com.maodouchat.util.StickerPolicy.pushRecent(recentStickers, item)
                                    }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        } else {
            var emojiSearch by rememberSaveable { mutableStateOf("") }
            val filteredEmojis = remember(emojiSearch) {
                val q = emojiSearch.trim()
                if (q.isEmpty()) {
                    BUILT_IN_EMOJIS
                } else {
                    // emoji itself or common keyword aliases
                    val aliases = EMOJI_SEARCH_ALIASES
                    BUILT_IN_EMOJIS.filter { emoji ->
                        emoji.contains(q) ||
                            aliases[emoji].orEmpty().any { it.contains(q, ignoreCase = true) }
                    }
                }
            }
            OutlinedTextField(
                value = emojiSearch,
                onValueChange = { emojiSearch = it.take(64) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_emoji_search_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Outline
                )
            )
            if (filteredEmojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chat_emoji_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            } else {
                // 点击表情：上屏 + 记录最近使用（去重，本地按账号隔离）
                val pushRecentEmoji: (String) -> Unit = { emoji ->
                    onEmojiClick(emoji)
                    com.maodouchat.util.EmojiRecentPreferences.recordRecent(context, emoji)
                    recentEmojis = com.maodouchat.util.EmojiRecentPreferences.getRecent(context)
                }
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    // 最近使用行：仅无搜索词时展示
                    if (emojiSearch.isBlank() && recentEmojis.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_emoji_recent),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            recentEmojis.forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { pushRecentEmoji(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(item, fontSize = 22.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    filteredEmojis.chunked(6).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowItems.forEach { item ->
                                PressScaleGlyphItem(
                                    glyph = item,
                                    fontSize = 24.sp,
                                    enabled = true,
                                    onClick = { pushRecentEmoji(item) }
                                )
                            }
                            repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showPackManager) {
        // 远程贴纸包（OnDemandStickerStore）：按需拉取清单 + 下载，B1 包体瘦身闭环
        var remotePacks by remember { mutableStateOf<List<String>>(emptyList()) }
        var remoteStatus by remember { mutableStateOf<String?>(null) }
        var remoteDownloading by remember { mutableStateOf<String?>(null) }
        val remoteScope = rememberCoroutineScope()
        val remoteContext = context
        fun refreshRemotePacks() {
            remoteScope.launch {
                remoteStatus = null
                com.maodouchat.slim.OnDemandStickerStore.refreshManifest(remoteContext)
                    .onSuccess { remotePacks = it }
                    .onFailure { remoteStatus = remoteContext.getString(R.string.sticker_store_download_failed) }
            }
        }
        fun downloadRemotePack(packId: String) {
            if (remoteDownloading != null) return
            remoteScope.launch {
                remoteDownloading = packId
                remoteStatus = null
                val result = com.maodouchat.slim.OnDemandStickerStore.ensurePack(remoteContext, packId)
                remoteStatus = result.message
                remoteDownloading = null
            }
        }
        AlertDialog(
            onDismissRequest = { showPackManager = false },
            title = { Text(stringResource(R.string.sticker_pack_manage)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                ) {
                    com.maodouchat.util.StickerCatalog.BUILT_IN_PACKS.forEach { pack ->
                        val enabled = pack.id in enabledPackIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = com.maodouchat.util.StickerPolicy.togglePackEnabled(
                                        enabledIds = enabledPackIds,
                                        packId = pack.id,
                                        enable = !enabled
                                    )
                                    enabledPackIds = next
                                    com.maodouchat.util.StickerPreferences.setEnabledPackIds(context, next)
                                    if (!enabled && stickerTab == pack.id) {
                                        stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = stickerPackLabel(pack.nameKey),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(if (enabled) R.string.sticker_pack_enabled else R.string.sticker_pack_disabled),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enabled) Primary else TextSecondary
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.textHint.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.sticker_store_remote_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { refreshRemotePacks() }) {
                            Text(stringResource(R.string.sticker_store_remote_refresh))
                        }
                    }
                    if (remoteStatus != null) {
                        Text(
                            remoteStatus.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    if (remotePacks.isEmpty() && remoteStatus == null) {
                        Text(
                            stringResource(R.string.sticker_store_remote_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    remotePacks.forEach { packId ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                packId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (remoteDownloading == packId) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                TextButton(onClick = { downloadRemotePack(packId) }) {
                                    Text(stringResource(R.string.sticker_store_remote_download))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPackManager = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }
}

