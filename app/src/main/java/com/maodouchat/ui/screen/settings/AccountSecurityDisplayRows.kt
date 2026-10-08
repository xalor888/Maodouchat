package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

/**
 * 账号安全页的展示行组件簇（2026-10-08 从 `SettingsAccountSecurity.kt` 按簇拆出；
 * 密码/账号变更对话框留在原文件）。纯搬移，零改动（已是 `internal`）。
 */

@Composable
internal fun DeviceRow(
    device: DeviceInfoDto,
    currentDeviceId: Int,
    isRemoving: Boolean,
    isRenaming: Boolean,
    isConfirming: Boolean,
    isMutationInProgress: Boolean,
    canConfirm: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit
) {
    val isCurrent = device.isCurrent || device.deviceId == currentDeviceId
    val isPending = device.status == "PENDING"
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = if (isCurrent) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (device.deviceName.isBlank()) stringResource(R.string.account_device_fallback, device.deviceId) else device.deviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.account_current_device), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (isPending) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.account_pending_device), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    stringResource(R.string.account_device_fingerprint, device.deviceId, device.identityKey.take(8), device.identityKey.takeLast(6)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPending) {
                    Text(
                        if (isCurrent) stringResource(R.string.account_pending_current_hint) else stringResource(R.string.account_pending_other_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRename, enabled = !isMutationInProgress) {
                if (isRenaming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else Text(stringResource(R.string.account_rename_device), color = MaterialTheme.colorScheme.primary)
            }
            if (isPending && !isCurrent) {
                TextButton(onClick = onConfirm, enabled = canConfirm && !isMutationInProgress) {
                    if (isConfirming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(R.string.account_approve_device), color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!isCurrent) {
                TextButton(onClick = onRemove, enabled = !isMutationInProgress) {
                    if (isRemoving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(if (isPending) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SecurityStatusCard(
    e2eeReady: Boolean,
    appLockOn: Boolean,
    confirmedDeviceCount: Int,
    fingerprint: String?
) {
    val accent = if (e2eeReady) OnlineGreen else Error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.security_center_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(if (e2eeReady) R.string.security_e2ee_ready else R.string.security_e2ee_not_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(if (e2eeReady) R.string.security_e2ee_hint else R.string.security_e2ee_hint_degraded),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.security_local_db_encrypted),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(if (appLockOn) R.string.security_app_lock_on else R.string.security_app_lock_off),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        val shortFp = fingerprint
            ?.replace(" ", "")
            ?.take(8)
            ?.uppercase(Locale.US)
            ?: stringResource(R.string.security_fingerprint_unavailable)
        Text(
            pluralStringResource(R.plurals.security_devices_confirmed, confirmedDeviceCount, confirmedDeviceCount, shortFp),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
    }
}

@Composable
internal fun SecuritySectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

@Composable
internal fun SecurityGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
    ) { content() }
}

@Composable
internal fun HorizontalDividerLite() {
    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary, modifier = Modifier.width(108.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ActionRow(label: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clickableRow(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatPalette.current.textHint
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) LocalChatPalette.current.textSecondary else LocalChatPalette.current.textHint
                )
            }
        }
        Text("›", color = LocalChatPalette.current.textHint, fontSize = 18.sp)
    }
}

@Composable
internal fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled) { onClick() }
