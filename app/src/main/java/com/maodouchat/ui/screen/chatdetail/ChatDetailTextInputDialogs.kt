package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 聊天页的「文本输入类」对话框（G130 从 `ChatDetailMiscDialogs.kt` 拆出，原 351 行）。
 *
 * 三个声明：`GifSearchDialog`（GIF 网格 + 搜索 + 点击发送）、
 * `TranslationLanguageDialog`（翻译目标语言多选）、
 * `QuickPhrasesDialog`（快捷短语列表的新增/编辑/删除）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun GifSearchDialog(
    onPickUri: (Uri, String?) -> Unit,
    onBrowseFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<com.maodouchat.util.LocalGifItem>>(emptyList()) }
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(granted) {
        if (!granted) {
            loading = false
            items = emptyList()
            return@LaunchedEffect
        }
        loading = true
        items = withContext(Dispatchers.IO) {
            com.maodouchat.util.GifLibrary.queryLocalGifs(context)
        }
        loading = false
    }

    val recentIds = remember { com.maodouchat.util.GifSearchPreferences.getRecentIds(context) }
    val filtered = remember(items, query, recentIds) {
        com.maodouchat.util.GifSearchPolicy.filterAndSort(items, query, recentIds)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gif_search_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.gif_search_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!granted) {
                    Text(
                        text = stringResource(R.string.gif_search_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRequestPermission) {
                        Text(stringResource(R.string.gif_search_grant))
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gif_search_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Outline
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.gif_search_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalChatPalette.current.textSecondary
                                )
                            }
                        }
                        filtered.isEmpty() -> {
                            Text(
                                text = stringResource(
                                    if (query.isBlank()) R.string.gif_search_empty
                                    else R.string.gif_search_empty_query
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                        else -> {
                            Text(
                                text = pluralStringResource(R.plurals.gif_search_count, filtered.size, filtered.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalChatPalette.current.textHint,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                filtered.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row.forEach { item ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(88.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                                    .clickable {
                                                        onPickUri(Uri.parse(item.uriString), item.id)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                coil.compose.AsyncImage(
                                                    model = item.uriString,
                                                    contentDescription = item.displayName,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            }
                                        }
                                        repeat(3 - row.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onBrowseFiles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gif_search_browse), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
internal fun TranslationLanguageDialog(
    translatedLanguages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var languageSearch by rememberSaveable { mutableStateOf("") }
    val q = languageSearch.trim()
    val labeledOptions = translationLanguageOptions.map { option ->
        option to stringResource(option.labelResource)
    }
    val filteredLanguages = if (q.isEmpty()) {
        labeledOptions
    } else {
        labeledOptions.filter { (option, label) ->
            label.contains(q, ignoreCase = true) ||
                option.wireValue.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_translation_language_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedTextField(
                    value = languageSearch,
                    onValueChange = { languageSearch = it.take(64) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    placeholder = { Text(stringResource(R.string.chat_translation_language_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        cursorColor = Primary
                    )
                )
                if (filteredLanguages.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_translation_language_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    filteredLanguages.forEach { (language, label) ->
                        TextButton(
                            onClick = { onSelect(language.wireValue) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (language.wireValue in translatedLanguages) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.chat_translation_available),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun QuickPhrasesDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var phrases by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getPhrases(context))
    }
    var customIds by remember {
        mutableStateOf(com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet())
    }
    var newPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quick_phrases), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = newPhrase,
                    onValueChange = {
                        newPhrase = it.take(com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_quick_phrases_add_hint, com.maodouchat.util.QuickPhrasePolicy.MAX_PHRASE_LENGTH)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (error != null) {
                        Text(error.orEmpty(), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = newPhrase.isNotBlank(),
                        onClick = {
                            if (!com.maodouchat.util.QuickPhrasePolicy.isAddable(
                                    com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context),
                                    newPhrase
                                )
                            ) {
                                error = context.getString(R.string.chat_quick_phrases_add_invalid)
                                return@TextButton
                            }
                            com.maodouchat.util.QuickPhrasePreferences.addPhrase(context, newPhrase.trim())
                            phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                            customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                            newPhrase = ""
                            Toast.makeText(context, context.getString(R.string.chat_quick_phrases_added), Toast.LENGTH_SHORT).show()
                        }
                    ) { Text(stringResource(R.string.chat_quick_phrases_add)) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    phrases.forEach { phrase ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { onPick(phrase) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    phrase,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (phrase in customIds) {
                                IconButton(onClick = {
                                    com.maodouchat.util.QuickPhrasePreferences.removePhrase(context, phrase)
                                    phrases = com.maodouchat.util.QuickPhrasePreferences.getPhrases(context)
                                    customIds = com.maodouchat.util.QuickPhrasePreferences.getCustomPhrases(context).toSet()
                                }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = LocalChatPalette.current.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_quick_phrases_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } }
    )
}
