package com.maodouchat.ui.screen.chatdetail

// 文本辅助对话框簇：从 ChatDetailTextInputDialogs 拆出的同包对话框（翻译目标语言多选/快捷短语管理）。

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

