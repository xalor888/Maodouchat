package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.maodouchat.ai.AiWritingStylePolicy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.maodouchat.network.AiAuditLogResponse
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import android.widget.Toast
import com.maodouchat.ui.theme.Error
import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip

/**
 * 「AI 与隐私」页（G99 从 `SettingsSubScreens.kt` 拆出，原 633 行）。
 *
 * 聊天内 AI 开关 + 本机授权 + 调用审计。含它专属的 `AiAuditLogRow`。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 * 纯搬移，不改判断。
 */

/**
 * 「AI 与隐私」页 — 聊天内 AI 开关 + 本机授权 + 调用审计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPrivacySettingsScreen(
    onBack: () -> Unit = {},
    onOpenAgent: () -> Unit = {},
    viewModel: AiPrivacySettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetConsentDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var imageOcrEnabled by remember { mutableStateOf(com.maodouchat.ai.ImageOcrPreferences.isEnabled(context)) }
    var autoTranslateEnabled by remember { mutableStateOf(com.maodouchat.ai.AiPrivacyPreferences.autoTranslateIncoming(context)) }
    var providerName by remember {
        mutableStateOf(com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)?.name.orEmpty())
    }
    var providerBaseUrl by remember {
        mutableStateOf(com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)?.baseUrl.orEmpty())
    }
    var providerModel by remember {
        mutableStateOf(com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)?.model.orEmpty())
    }
    var providerApiKey by remember {
        mutableStateOf(com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)?.apiKey.orEmpty())
    }
    val activeProvider = remember { com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context) }
    var providerProtocol by remember {
        mutableStateOf(activeProvider?.protocol ?: com.maodouchat.ai.agent.LocalAiProtocol.OPENAI_CHAT_COMPLETIONS)
    }
    var providerOrg by remember { mutableStateOf(activeProvider?.organization.orEmpty()) }
    var providerAnthropicVersion by remember {
        mutableStateOf(activeProvider?.anthropicVersion?.ifBlank { "2023-06-01" } ?: "2023-06-01")
    }
    var providerExtraHeaders by remember { mutableStateOf(activeProvider?.extraHeadersJson?.ifBlank { "{}" } ?: "{}") }
    var providerTemperature by remember { mutableStateOf(activeProvider?.temperature?.toString().orEmpty()) }
    var providerTopP by remember { mutableStateOf(activeProvider?.topP?.toString().orEmpty()) }
    var providerMaxTokens by remember { mutableStateOf((activeProvider?.maxTokens ?: 4096).toString()) }
    var providerContextWindow by remember { mutableStateOf((activeProvider?.contextWindowTokens ?: 128000).toString()) }
    var providerHistoryLimit by remember { mutableStateOf((activeProvider?.historyMessageLimit ?: 24).toString()) }
    var providerTimeout by remember { mutableStateOf((activeProvider?.timeoutSeconds ?: 120).toString()) }
    var providerStream by remember { mutableStateOf(activeProvider?.stream ?: true) }
    var providerSupportsVision by remember { mutableStateOf(activeProvider?.supportsVision ?: false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_ai_privacy), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            val allAiReady = state.userEnabled && state.aiConsentAccepted && autoTranslateEnabled && imageOcrEnabled
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = if (allAiReady) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_privacy_enable_all_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.ai_privacy_enable_all_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.enableAllDefaults()
                            imageOcrEnabled = true
                            com.maodouchat.ai.ImageOcrPreferences.setEnabled(context, true)
                            autoTranslateEnabled = true
                            com.maodouchat.ai.AiPrivacyPreferences.setAutoTranslateIncoming(context, true)
                            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                                runCatching {
                                    com.maodouchat.MaodouchatApp.instance.imageOcrAutoIndexer.runOnce()
                                }
                            }
                        },
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (allAiReady) stringResource(R.string.ai_privacy_enable_all_active)
                            else stringResource(R.string.ai_privacy_enable_all_btn)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SecurityGroup {
                // 总开关：与聊天内主入口 / 长按场景入口配套（M5-1）
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_global),
                    subtitle = if (state.userEnabled) {
                        stringResource(R.string.ai_privacy_global_enabled_hint)
                    } else {
                        stringResource(R.string.ai_privacy_global_disabled_hint)
                    },
                    checked = state.userEnabled,
                    enabled = !state.isSaving && !state.isLoading,
                    onCheckedChange = { viewModel.setUserAiEnabled(it) }
                )
                HorizontalDividerLite()
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_local_consent),
                    subtitle = stringResource(R.string.ai_privacy_local_consent_hint),
                    checked = state.aiConsentAccepted,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setAiConsentAccepted(it) }
                )
                HorizontalDividerLite()
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_auto_translate),
                    subtitle = stringResource(R.string.ai_privacy_auto_translate_hint),
                    checked = autoTranslateEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { enabled ->
                        autoTranslateEnabled = enabled
                        com.maodouchat.ai.AiPrivacyPreferences.setAutoTranslateIncoming(context, enabled)
                    }
                )
                HorizontalDividerLite()
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_local_safety),
                    subtitle = stringResource(R.string.ai_privacy_local_safety_hint),
                    checked = state.localSafetyEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setLocalSafetyEnabled(it) }
                )
                HorizontalDividerLite()
                // 自动图片 OCR：识别图内文字并写入搜索索引（本机开关，默认开）
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_image_ocr),
                    subtitle = stringResource(R.string.ai_privacy_image_ocr_hint),
                    checked = imageOcrEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { enabled ->
                        imageOcrEnabled = enabled
                        com.maodouchat.ai.ImageOcrPreferences.setEnabled(context, enabled)
                        if (enabled) {
                            // 开启后立即扫描一轮，让已有图片尽快可被搜索
                            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                                runCatching {
                                    com.maodouchat.MaodouchatApp.instance.imageOcrAutoIndexer.runOnce()
                                }
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.agent_provider_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.agent_provider_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_provider_protocol),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        com.maodouchat.ai.agent.LocalAiProtocol.entries.forEach { proto ->
                            val label = when (proto) {
                                com.maodouchat.ai.agent.LocalAiProtocol.OPENAI_CHAT_COMPLETIONS ->
                                    stringResource(R.string.agent_protocol_chat_completions)
                                com.maodouchat.ai.agent.LocalAiProtocol.OPENAI_RESPONSES ->
                                    stringResource(R.string.agent_protocol_responses)
                                com.maodouchat.ai.agent.LocalAiProtocol.ANTHROPIC_MESSAGES ->
                                    stringResource(R.string.agent_protocol_anthropic)
                            }
                            FilterChip(
                                selected = providerProtocol == proto,
                                onClick = { providerProtocol = proto },
                                label = { Text(label) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerName,
                        onValueChange = { providerName = it.take(80) },
                        label = { Text(stringResource(R.string.agent_provider_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerBaseUrl,
                        onValueChange = { providerBaseUrl = it.take(240) },
                        label = { Text(stringResource(R.string.agent_provider_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerModel,
                        onValueChange = { providerModel = it.take(80) },
                        label = { Text(stringResource(R.string.agent_provider_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerApiKey,
                        onValueChange = { providerApiKey = it.take(200) },
                        label = { Text(stringResource(R.string.agent_provider_api_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (providerProtocol != com.maodouchat.ai.agent.LocalAiProtocol.ANTHROPIC_MESSAGES) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = providerOrg,
                            onValueChange = { providerOrg = it.take(80) },
                            label = { Text(stringResource(R.string.agent_provider_organization)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = providerAnthropicVersion,
                            onValueChange = { providerAnthropicVersion = it.take(40) },
                            label = { Text(stringResource(R.string.agent_provider_anthropic_version)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerExtraHeaders,
                        onValueChange = { providerExtraHeaders = it.take(1_000) },
                        label = { Text(stringResource(R.string.agent_provider_extra_headers)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_provider_context_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = providerContextWindow,
                        onValueChange = { providerContextWindow = it.filter(Char::isDigit).take(8) },
                        label = { Text(stringResource(R.string.agent_provider_context_window)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerHistoryLimit,
                        onValueChange = { providerHistoryLimit = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.agent_provider_history_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerMaxTokens,
                        onValueChange = { providerMaxTokens = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.agent_provider_max_tokens)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerTemperature,
                        onValueChange = { providerTemperature = it.take(8) },
                        label = { Text(stringResource(R.string.agent_provider_temperature)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerTopP,
                        onValueChange = { providerTopP = it.take(8) },
                        label = { Text(stringResource(R.string.agent_provider_top_p)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerTimeout,
                        onValueChange = { providerTimeout = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.agent_provider_timeout)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(
                        title = stringResource(R.string.agent_provider_stream),
                        subtitle = stringResource(R.string.agent_provider_stream_hint),
                        checked = providerStream,
                        onCheckedChange = { providerStream = it }
                    )
                    HorizontalDividerLite()
                    SwitchRow(
                        title = stringResource(R.string.agent_provider_supports_vision),
                        subtitle = stringResource(R.string.agent_provider_supports_vision_hint),
                        checked = providerSupportsVision,
                        onCheckedChange = { providerSupportsVision = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        TextButton(
                            onClick = {
                                val current = com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)
                                    ?: com.maodouchat.ai.agent.LocalAiProviderStore.newProviderDraft(providerProtocol)
                                com.maodouchat.ai.agent.LocalAiProviderStore.upsertProvider(
                                    context,
                                    current.copy(
                                        name = providerName.ifBlank { current.name },
                                        baseUrl = providerBaseUrl.trim().trimEnd('/'),
                                        model = providerModel.trim(),
                                        apiKey = providerApiKey.trim(),
                                        protocol = providerProtocol,
                                        organization = providerOrg.trim(),
                                        anthropicVersion = providerAnthropicVersion.trim().ifBlank { "2023-06-01" },
                                        extraHeadersJson = providerExtraHeaders.trim().ifBlank { "{}" },
                                        temperature = providerTemperature.trim().toDoubleOrNull(),
                                        topP = providerTopP.trim().toDoubleOrNull(),
                                        maxTokens = providerMaxTokens.toIntOrNull() ?: 4_096,
                                        contextWindowTokens = providerContextWindow.toIntOrNull() ?: 128_000,
                                        historyMessageLimit = providerHistoryLimit.toIntOrNull() ?: 24,
                                        timeoutSeconds = providerTimeout.toIntOrNull() ?: 120,
                                        stream = providerStream,
                                        supportsVision = providerSupportsVision
                                    )
                                )
                                Toast.makeText(context, R.string.agent_provider_saved, Toast.LENGTH_SHORT).show()
                            }
                        ) { Text(stringResource(R.string.agent_provider_save)) }
                        if (state.userEnabled && state.aiConsentAccepted) {
                            TextButton(onClick = onOpenAgent) { Text(stringResource(R.string.agent_open)) }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_writing_style),
                    subtitle = stringResource(R.string.ai_privacy_writing_style_hint),
                    checked = state.writingStyleEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setWritingStyleEnabled(it) }
                )
                if (state.writingStyleEnabled) {
                    HorizontalDividerLite()
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.ai_privacy_writing_style_preset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AiWritingStylePolicy.Preset.NONE to R.string.ai_privacy_writing_style_preset_none,
                                AiWritingStylePolicy.Preset.CONCISE to R.string.ai_privacy_writing_style_preset_concise,
                                AiWritingStylePolicy.Preset.FORMAL to R.string.ai_privacy_writing_style_preset_formal,
                                AiWritingStylePolicy.Preset.WARM to R.string.ai_privacy_writing_style_preset_warm,
                                AiWritingStylePolicy.Preset.PROFESSIONAL to R.string.ai_privacy_writing_style_preset_professional,
                                AiWritingStylePolicy.Preset.CASUAL to R.string.ai_privacy_writing_style_preset_casual,
                                AiWritingStylePolicy.Preset.WITTY to R.string.ai_privacy_writing_style_preset_witty,
                                AiWritingStylePolicy.Preset.EMPATHETIC to R.string.ai_privacy_writing_style_preset_empathetic,
                                AiWritingStylePolicy.Preset.DIRECT to R.string.ai_privacy_writing_style_preset_direct,
                                AiWritingStylePolicy.Preset.ENTHUSIASTIC to R.string.ai_privacy_writing_style_preset_enthusiastic,
                                AiWritingStylePolicy.Preset.DIPLOMATIC to R.string.ai_privacy_writing_style_preset_diplomatic
                            ).forEach { (preset, labelRes) ->
                                FilterChip(
                                    selected = state.writingStylePresetId == preset.id,
                                    onClick = { viewModel.setWritingStylePreset(preset.id) },
                                    enabled = !state.isSaving,
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.writingStyleCustomNote,
                            onValueChange = { viewModel.setWritingStyleCustomNote(it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isSaving,
                            singleLine = false,
                            maxLines = 3,
                            label = { Text(stringResource(R.string.ai_privacy_writing_style_custom)) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        R.string.ai_privacy_writing_style_custom_count,
                                        state.writingStyleCustomNote.length,
                                        AiWritingStylePolicy.MAX_CUSTOM_CHARS
                                    )
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.ai_privacy_writing_style_local_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.clearWritingStyle() },
                            enabled = !state.isSaving
                        ) {
                            Text(stringResource(R.string.ai_privacy_writing_style_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_privacy_recent_calls), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ai_privacy_audit_hint), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    }
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        Text(stringResource(R.string.common_refresh))
                    }
                }
                HorizontalDividerLite()
                when {
                    state.isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ai_privacy_loading), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.auditLogs.isEmpty() -> {
                        Text(
                            stringResource(R.string.ai_privacy_empty),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    else -> {
                        var auditSearch by rememberSaveable { mutableStateOf("") }
                        val filteredAuditLogs = remember(state.auditLogs, auditSearch) {
                            val query = auditSearch.trim()
                            if (query.isBlank()) {
                                state.auditLogs
                            } else {
                                state.auditLogs.filter { log ->
                                    log.feature.contains(query, ignoreCase = true) ||
                                        log.status.contains(query, ignoreCase = true) ||
                                        log.model.orEmpty().contains(query, ignoreCase = true) ||
                                        log.error.orEmpty().contains(query, ignoreCase = true) ||
                                        log.chatId.orEmpty().contains(query, ignoreCase = true)
                                }
                            }
                        }
                        if (state.auditLogs.size >= 5) {
                            OutlinedTextField(
                                value = auditSearch,
                                onValueChange = { auditSearch = it.take(120) },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.ai_privacy_audit_search_hint)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        if (filteredAuditLogs.isEmpty()) {
                            Text(
                                stringResource(R.string.ai_privacy_audit_search_empty),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalChatPalette.current.textSecondary
                            )
                        } else {
                            filteredAuditLogs.forEachIndexed { index, log ->
                                AiAuditLogRow(log = log)
                                if (index != filteredAuditLogs.lastIndex) HorizontalDividerLite()
                            }
                        }
                    }
                }
            }

            if (!state.infoMessage.isNullOrBlank() || !state.errorMessage.isNullOrBlank()) {
                Text(
                    text = state.infoMessage ?: state.errorMessage.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessage == null) MaterialTheme.colorScheme.primary else Error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_privacy_reset_consent), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ai_privacy_reset_consent_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    }
                    TextButton(
                        onClick = { showResetConsentDialog = true },
                        enabled = !state.isSaving
                    ) { Text(stringResource(R.string.ai_privacy_reset_consent), color = MaterialTheme.colorScheme.error) }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetConsentDialog) {
        AlertDialog(
            onDismissRequest = { showResetConsentDialog = false },
            title = { Text(stringResource(R.string.ai_privacy_reset_consent)) },
            text = { Text(stringResource(R.string.ai_privacy_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConsentDialog = false
                    viewModel.revokeLocalConsent()
                }) { Text(stringResource(R.string.ai_privacy_reset_confirm_ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConsentDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun AiAuditLogRow(log: AiAuditLogResponse) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(aiFeatureLabel(log.feature), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    aiStatusLabel(log.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = aiStatusColor(log.status)
                )
            }
            Text(
                listOfNotNull(
                    log.model?.takeIf { it.isNotBlank() },
                    stringResource(R.string.ai_privacy_input_chars, log.inputChars),
                    if (log.contextMessages > 0) stringResource(R.string.ai_privacy_context_messages, log.contextMessages) else null,
                    log.durationMs?.let { "${it}ms" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
            log.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(formatAuditTime(log.createdAt), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
    }
}

@Composable
private fun aiFeatureLabel(feature: String): String = when (feature.lowercase()) {
    "rewrite" -> stringResource(R.string.ai_feature_rewrite)
    "suggest_replies" -> stringResource(R.string.ai_feature_suggest_replies)
    "summarize" -> stringResource(R.string.ai_feature_summarize)
    "transcribe_voice" -> stringResource(R.string.ai_feature_transcribe_voice)
    "translate_message" -> stringResource(R.string.ai_feature_translate_message)
    "semantic_search" -> stringResource(R.string.ai_feature_semantic_search)
    "global_semantic_search" -> stringResource(R.string.ai_feature_global_semantic_search)
    "group_assistant" -> stringResource(R.string.ai_feature_group_assistant)
    "image_analyze" -> stringResource(R.string.ai_feature_image_analyze)
    "file_analyze" -> stringResource(R.string.ai_feature_file_analyze)
    else -> if (feature.isBlank()) stringResource(R.string.ai_feature_generic) else feature
}

@Composable
private fun aiStatusLabel(status: String): String = when (status.lowercase()) {
    "success" -> stringResource(R.string.ai_status_success)
    "failed", "error" -> stringResource(R.string.ai_status_failed)
    "disabled" -> stringResource(R.string.ai_status_disabled)
    "rate_limited" -> stringResource(R.string.ai_status_rate_limited)
    else -> if (status.isBlank()) stringResource(R.string.ai_status_unknown) else status
}

@Composable
private fun aiStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> MaterialTheme.colorScheme.primary
    "failed", "error", "rate_limited" -> Error
    else -> LocalChatPalette.current.textHint
}
