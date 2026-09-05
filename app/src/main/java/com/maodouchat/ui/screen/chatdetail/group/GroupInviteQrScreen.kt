package com.maodouchat.ui.screen.chatdetail.group

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupInviteQrScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var inviteExpirySeconds by rememberSaveable { mutableLongStateOf(7L * 24L * 60L * 60L) }
    var inviteMaxUses by rememberSaveable { mutableIntStateOf(100) }
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.group_detail_share_invite)

    LaunchedEffect(state.isLoading, state.canManageGroup, state.groupInvitePayload) {
        if (!state.isLoading && state.canManageGroup && state.groupInvitePayload.isBlank() && !state.isLoadingInvite) {
            viewModel.loadGroupInvite(expiresInSeconds = inviteExpirySeconds, maxUses = inviteMaxUses)
        }
    }

    val shareInvite: () -> Unit = {
        val payload = state.groupInvitePayload
        if (payload.isNotBlank()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
        GroupDetailFeedbackDialog(state, viewModel)
        Scaffold(
            containerColor = LocalChatPalette.current.chatBackground,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.group_detail_invite_qr), color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.loadGroupInvite(
                                    rotate = true,
                                    expiresInSeconds = inviteExpirySeconds,
                                    maxUses = inviteMaxUses
                                )
                            },
                            enabled = state.canManageGroup && !state.isLoadingInvite
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh))
                        }
                        IconButton(
                            onClick = shareInvite,
                            enabled = state.groupInvitePayload.isNotBlank() && !state.isLoadingInvite
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.group_detail_share))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.shadow(1.dp)
                )
            }
        ) { padding ->
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                !state.canManageGroup -> {
                    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.group_detail_invite_admin_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    GroupInviteContent(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        payload = state.groupInvitePayload,
                        isLoading = state.isLoadingInvite,
                        expiresAt = state.inviteExpiresAt,
                        maxUses = state.inviteMaxUses,
                        usedCount = state.inviteUsedCount,
                        remainingUses = state.inviteRemainingUses,
                        expiresInSeconds = inviteExpirySeconds,
                        selectedMaxUses = inviteMaxUses,
                        onExpiryChange = { inviteExpirySeconds = it },
                        onMaxUsesChange = { inviteMaxUses = it }
                    )
                }
            }
        }
    }
}

@Composable
fun GroupInviteContent(
    modifier: Modifier,
    payload: String,
    isLoading: Boolean,
    expiresAt: Long,
    maxUses: Int,
    usedCount: Int,
    remainingUses: Int,
    expiresInSeconds: Long,
    selectedMaxUses: Int,
    onExpiryChange: (Long) -> Unit,
    onMaxUsesChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val inviteCopiedMessage = stringResource(R.string.group_detail_invite_copied)
    val bitmap = remember(payload) {
        payload.takeIf { it.isNotBlank() }?.let { QrCodeGenerator.generateBitmap(it, 720) }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .size(248.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.group_detail_invite_qr),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                else -> Text(stringResource(R.string.group_detail_qr_failed), color = LocalChatPalette.current.textHint)
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary,
            textAlign = TextAlign.Center
        )
        if (payload.isNotBlank()) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("group_invite", payload))
                Toast.makeText(
                    context,
                    inviteCopiedMessage,
                    Toast.LENGTH_SHORT
                ).show()
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.group_detail_invite_copy))
            }
        }
        if (expiresAt > 0) {
            Text(
                stringResource(
                    R.string.group_detail_invite_status,
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        LocalConfiguration.current.locales[0]
                    ).format(Date(expiresAt)),
                    usedCount,
                    maxUses,
                    remainingUses
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
        Text(
            stringResource(R.string.group_detail_invite_expiry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                24L * 60L * 60L to stringResource(R.string.group_detail_invite_one_day),
                3L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_three_days),
                7L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_seven_days),
                30L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_thirty_days)
            ).forEach { (seconds, label) ->
                InviteChoice(selected = expiresInSeconds == seconds, label = label) { onExpiryChange(seconds) }
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_max_uses),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(1, 5, 10, 50, 100, 200, 500, 1000).forEach { uses ->
                InviteChoice(selected = selectedMaxUses == uses, label = uses.toString()) { onMaxUsesChange(uses) }
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_limit_note),
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textHint,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun InviteChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            RoundedCornerShape(8.dp)
        )
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary)
    }
}
