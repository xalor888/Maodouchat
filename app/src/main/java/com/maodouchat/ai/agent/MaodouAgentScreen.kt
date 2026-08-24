package com.maodouchat.ai.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaodouAgentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MaodouAgentService.init(context) }
    val messages = MaodouAgentService.messages
    val pending = MaodouAgentService.pendingApproval.value
    val error = MaodouAgentService.errorMessage.value
    val running = MaodouAgentService.ballState.value == MaodouAgentService.BallState.RUNNING
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.agent_title), style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = { MaodouAgentService.newSession(context) }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.agent_new_session))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        Text(
            stringResource(R.string.agent_privacy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.filter { it.role != "system" && it.role != "tool" }, key = { it.hashCode() to it.content }) { message ->
                val mine = message.role == "user"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        if (mine) stringResource(R.string.agent_role_you) else stringResource(R.string.agent_role_assistant),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (pending != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(stringResource(R.string.agent_approval_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(pending.preview, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { MaodouAgentService.rejectPending(context) }) {
                        Text(stringResource(R.string.agent_reject))
                    }
                    Button(onClick = { MaodouAgentService.approvePending(context) }) {
                        Text(stringResource(R.string.agent_approve))
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(4_000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                enabled = !running && pending == null
            )
            Spacer(Modifier.padding(6.dp))
            Button(
                onClick = {
                    val text = draft
                    draft = ""
                    MaodouAgentService.send(context, text)
                },
                enabled = draft.isNotBlank() && !running && pending == null
            ) {
                Text(stringResource(R.string.agent_send))
            }
        }
    }
}
