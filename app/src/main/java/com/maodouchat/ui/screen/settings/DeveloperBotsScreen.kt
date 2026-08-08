package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class BotUi(
    val id: String,
    val name: String,
    val username: String,
    val tokenPrefix: String,
    val webhookUrl: String,
    val enabled: Boolean,
    val tokenOnce: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperBotsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bots by remember { mutableStateOf<List<BotUi>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var lastToken by remember { mutableStateOf<String?>(null) }
    var activeBotActionId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<BotUi?>(null) }

    suspend fun reload() {
        loading = true
        error = null
        try {
            val token = TokenManager.getInstance(context).getToken().orEmpty()
            val result = withContext(Dispatchers.IO) { ApiService.listBots(token) }
            result.onSuccess { raw ->
                bots = parseBots(raw)
            }.onFailure {
                error = it.message ?: context.getString(R.string.developer_bots_load_failed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: context.getString(R.string.developer_bots_load_failed)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_bots_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.developer_bots_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(120) },
                            label = { Text(stringResource(R.string.developer_bots_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(32).lowercase() },
                            label = { Text(stringResource(R.string.developer_bots_username)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (name.isBlank() || username.isBlank() || creating || activeBotActionId != null) return@Button
                                creating = true
                                error = null
                                info = null
                                scope.launch {
                                    try {
                                        val token = TokenManager.getInstance(context).getToken().orEmpty()
                                        val result = withContext(Dispatchers.IO) {
                                            ApiService.createBot(token, name.trim(), username.trim())
                                        }
                                        result.onSuccess { raw ->
                                            lastToken = extractTokenOnce(raw)
                                            info = context.getString(R.string.developer_bots_created)
                                            name = ""
                                            username = ""
                                            reload()
                                        }.onFailure {
                                            error = context.getString(R.string.developer_bots_create_failed)
                                        }
                                    } finally {
                                        creating = false
                                    }
                                }
                            },
                            enabled = !creating && activeBotActionId == null && name.isNotBlank() && username.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.developer_bots_create))
                        }
                    }
                }
            }
            lastToken?.let { tok ->
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.developer_bots_token_once),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { lastToken = null }) {
                                    Text(stringResource(R.string.developer_bots_token_hide))
                                }
                            }
                            Text(tok, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (loading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (bots.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.developer_bots_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(bots, key = { it.id }) { bot ->
                    var webhookDraft by remember(bot.id) { mutableStateOf(bot.webhookUrl) }
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(bot.name, fontWeight = FontWeight.SemiBold)
                            Text("@${bot.username}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.developer_bots_id, bot.id),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                stringResource(R.string.developer_bots_token_prefix, bot.tokenPrefix),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                stringResource(if (bot.enabled) R.string.developer_bots_enabled else R.string.developer_bots_disabled),
                                style = MaterialTheme.typography.labelSmall
                            )
                            OutlinedTextField(
                                value = webhookDraft,
                                onValueChange = { webhookDraft = it.take(500) },
                                label = { Text(stringResource(R.string.developer_bots_webhook_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (activeBotActionId != null || creating) return@Button
                                    val url = webhookDraft.trim()
                                    if (url.isNotEmpty() && !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                                        error = context.getString(R.string.developer_bots_webhook_invalid)
                                        return@Button
                                    }
                                    activeBotActionId = bot.id
                                    error = null
                                    info = null
                                    scope.launch {
                                        try {
                                            val token = TokenManager.getInstance(context).getToken().orEmpty()
                                            val result = withContext(Dispatchers.IO) {
                                                ApiService.setBotWebhook(
                                                    token,
                                                    bot.id,
                                                    url.ifBlank { null }
                                                )
                                            }
                                            result.onSuccess {
                                                info = context.getString(R.string.developer_bots_webhook_updated)
                                                reload()
                                            }.onFailure {
                                                error = it.message ?: context.getString(R.string.developer_bots_webhook_failed)
                                            }
                                        } finally {
                                            activeBotActionId = null
                                        }
                                    }
                                }, enabled = !creating && activeBotActionId == null) {
                                    Text(stringResource(R.string.developer_bots_webhook_save))
                                }
                                Button(onClick = {
                                    if (activeBotActionId != null || creating) return@Button
                                    activeBotActionId = bot.id
                                    error = null
                                    info = null
                                    scope.launch {
                                        try {
                                            val token = TokenManager.getInstance(context).getToken().orEmpty()
                                            val result = withContext(Dispatchers.IO) {
                                                ApiService.regenerateBotToken(token, bot.id)
                                            }
                                            result.onSuccess { raw ->
                                                lastToken = extractTokenOnce(raw)
                                                info = context.getString(R.string.developer_bots_token_rotated)
                                                reload()
                                            }.onFailure {
                                                error = it.message ?: context.getString(R.string.developer_bots_rotate_failed)
                                            }
                                        } finally {
                                            activeBotActionId = null
                                        }
                                    }
                                }, enabled = !creating && activeBotActionId == null) {
                                    Text(stringResource(R.string.developer_bots_rotate_token))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (activeBotActionId != null || creating) return@Button
                                    activeBotActionId = bot.id
                                    error = null
                                    info = null
                                    scope.launch {
                                        try {
                                            val token = TokenManager.getInstance(context).getToken().orEmpty()
                                            val result = withContext(Dispatchers.IO) {
                                                ApiService.setBotEnabled(token, bot.id, !bot.enabled)
                                            }
                                            result.onSuccess {
                                                info = context.getString(
                                                    if (!bot.enabled) R.string.developer_bots_enabled_feedback
                                                    else R.string.developer_bots_disabled_feedback
                                                )
                                                reload()
                                            }.onFailure {
                                                error = it.message ?: context.getString(R.string.developer_bots_enable_failed)
                                            }
                                        } finally {
                                            activeBotActionId = null
                                        }
                                    }
                                }, enabled = !creating && activeBotActionId == null) {
                                    Text(stringResource(if (bot.enabled) R.string.developer_bots_disable else R.string.developer_bots_enable))
                                }
                                Button(
                                    onClick = { pendingDelete = bot },
                                    enabled = !creating && activeBotActionId == null
                                ) { Text(stringResource(R.string.developer_bots_delete)) }
                            }
                        }
                    }
                }
            }
            info?.let {
                item { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            error?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { bot ->
        AlertDialog(
            onDismissRequest = { if (activeBotActionId == null) pendingDelete = null },
            title = { Text(stringResource(R.string.developer_bots_delete_confirm_title)) },
            text = { Text(stringResource(R.string.developer_bots_delete_confirm_message, bot.name)) },
            confirmButton = {
                TextButton(
                    enabled = activeBotActionId == null,
                    onClick = {
                        if (activeBotActionId != null) return@TextButton
                        pendingDelete = null
                        activeBotActionId = bot.id
                        error = null
                        info = null
                        scope.launch {
                            try {
                                val token = TokenManager.getInstance(context).getToken().orEmpty()
                                val result = withContext(Dispatchers.IO) { ApiService.deleteBot(token, bot.id) }
                                result.onSuccess {
                                    info = context.getString(R.string.developer_bots_deleted)
                                    reload()
                                }.onFailure {
                                    error = it.message ?: context.getString(R.string.developer_bots_delete_failed)
                                }
                            } finally {
                                activeBotActionId = null
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.developer_bots_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun extractTokenOnce(raw: String?): String? {
    return runCatching { org.json.JSONObject(raw ?: "") }
        .getOrNull()?.optString("tokenOnce")?.ifBlank { null }
}

private fun parseBots(raw: String): List<BotUi> {
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val username = o.optString("username").trim()
            add(
                BotUi(
                    id = id,
                    name = o.optString("name").trim().ifBlank { username.ifBlank { id } },
                    username = username,
                    tokenPrefix = o.optString("tokenPrefix"),
                    webhookUrl = o.optString("webhookUrl"),
                    enabled = o.optBoolean("enabled", true),
                    tokenOnce = o.optString("tokenOnce")
                )
            )
        }
    }
}
