package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.QrCodeGenerator

/**
 * 聊天页的「安全验证」一族（G131 从 `ChatDetailMiscDialogs.kt` 拆出，原 269 行）。
 *
 * 四个声明：`SafetyCodeDialog`（安全码 + 已验证设备列表 + 二维码）、
 * `DeviceSafetyRow`（单台设备的信任态行）、`SafetyQrCard`（二维码卡片）、
 * `IdentityTrustState.toLabel()`（信任态文案）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun SafetyCodeDialog(
    contactName: String,
    contactId: String,
    trustState: SignalProtocol.IdentityTrustState,
    isGroup: Boolean,
    safetyCode: String?,
    warning: String?,
    currentUserId: String,
    currentDeviceId: Int,
    currentIdentityFingerprint: String,
    contactIdentityFingerprint: String?,
    deviceSafetyWarning: String?,
    isLoadingDeviceSafety: Boolean,
    deviceSafetyStates: List<SignalProtocol.DeviceSafetyState>,
    onDismiss: () -> Unit,
    onVerifyDevice: (Int) -> Unit
) {
    val context = LocalContext.current
    val sticky = com.maodouchat.crypto.SafetyCodePolicy.isStickyIdentityWarning(trustState)
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(safetyCode)
    AlertDialog(
        // CHANGED identity: back/outside dismiss still allowed, but primary path forces verify dialog content.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_safety_title, contactName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (sticky) {
                    Text(
                        stringResource(R.string.chat_safety_changed_sticky_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.unreadRed
                    )
                }
                deviceSafetyWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.unreadRed) }
                if (isLoadingDeviceSafety) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_safety_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                val devices = deviceSafetyStates
                var deviceSearch by remember { mutableStateOf("") }
                val filteredDevices = remember(devices, deviceSearch) {
                    val q = deviceSearch.trim()
                    if (q.isEmpty()) {
                        devices
                    } else {
                        devices.filter { device ->
                            device.deviceId.toString().contains(q, ignoreCase = true) ||
                                device.trustState.name.contains(q, ignoreCase = true) ||
                                device.safetyCode.orEmpty().contains(q, ignoreCase = true) ||
                                device.identityKey.orEmpty().contains(q, ignoreCase = true)
                        }
                    }
                }
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.chat_safety_status, if (isGroup) stringResource(R.string.chat_group_sender_key_enabled) else trustState.toLabel()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        displayCode ?: stringResource(R.string.chat_safety_not_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    displayCode?.let { code ->
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        context.getString(R.string.chat_safety_code),
                                        com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(code).orEmpty()
                                    )
                                )
                                Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) { Text(stringResource(R.string.chat_safety_copy_code)) }
                        if (!contactIdentityFingerprint.isNullOrBlank() && currentIdentityFingerprint.isNotBlank()) {
                            SafetyQrCard(
                                ownerUserId = currentUserId,
                                ownerDeviceId = currentDeviceId,
                                peerUserId = contactId,
                                peerDeviceId = 1,
                                ownerIdentityFingerprint = currentIdentityFingerprint,
                                peerIdentityFingerprint = contactIdentityFingerprint
                            )
                        }
                    }
                } else {
                    if (devices.size >= 4) {
                        OutlinedTextField(
                            value = deviceSearch,
                            onValueChange = { deviceSearch = it.take(64) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_safety_devices_search_hint)) },
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
                    }
                    if (filteredDevices.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_safety_devices_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textHint
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            filteredDevices.forEach { device ->
                                DeviceSafetyRow(
                                    device = device,
                                    ownerUserId = currentUserId,
                                    ownerDeviceId = currentDeviceId,
                                    ownerIdentityFingerprint = currentIdentityFingerprint,
                                    peerUserId = contactId,
                                    onVerify = { onVerifyDevice(device.deviceId) }
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.chat_safety_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    stringResource(R.string.chat_safety_format_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (sticky) R.string.chat_safety_review_later else R.string.common_done
                    )
                )
            }
        },
        dismissButton = {
            if (!sticky) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
            }
        }
    )
}

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun DeviceSafetyRow(
    device: SignalProtocol.DeviceSafetyState,
    ownerUserId: String,
    ownerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerUserId: String,
    onVerify: () -> Unit
) {
    val context = LocalContext.current
    val displayCode = com.maodouchat.crypto.SafetyCodePolicy.formatForDisplay(device.safetyCode)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (device.isCurrent) R.string.chat_device_current else R.string.chat_device_number, device.deviceId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(device.trustState.toLabel(), style = MaterialTheme.typography.labelMedium, color = if (device.trustState == SignalProtocol.IdentityTrustState.CHANGED) UnreadRed else Primary)
        }
        Text(
            displayCode ?: stringResource(R.string.chat_device_session_missing),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (displayCode == null) {
            Text(stringResource(R.string.chat_identity_fingerprint, device.identityKey.take(16)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } else {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            context.getString(R.string.chat_safety_code),
                            com.maodouchat.crypto.SafetyCodePolicy.formatForCopy(displayCode).orEmpty()
                        )
                    )
                    Toast.makeText(context, context.getString(R.string.chat_safety_code_copied), Toast.LENGTH_SHORT).show()
                }
            ) { Text(stringResource(R.string.chat_safety_copy_code)) }
            SafetyQrCard(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = device.deviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = device.identityFingerprint
            )
        }
        Button(onClick = onVerify, enabled = displayCode != null && device.trustState != SignalProtocol.IdentityTrustState.VERIFIED) {
            Text(stringResource(if (device.trustState == SignalProtocol.IdentityTrustState.VERIFIED) R.string.chat_verified else R.string.chat_mark_verified))
        }
    }
}

@Composable
internal fun SafetyQrCard(
    ownerUserId: String,
    ownerDeviceId: Int,
    peerUserId: String,
    peerDeviceId: Int,
    ownerIdentityFingerprint: String,
    peerIdentityFingerprint: String
) {
    val bitmap = remember(ownerUserId, ownerDeviceId, peerUserId, peerDeviceId, ownerIdentityFingerprint, peerIdentityFingerprint) {
        QrCodeGenerator.generateBitmap(
            QrCodeGenerator.encodeSafetyQrPayload(
                ownerUserId = ownerUserId,
                ownerDeviceId = ownerDeviceId,
                peerUserId = peerUserId,
                peerDeviceId = peerDeviceId,
                ownerIdentityFingerprint = ownerIdentityFingerprint,
                peerIdentityFingerprint = peerIdentityFingerprint
            ),
            320
        )
    }
    if (bitmap != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_identity_qr),
                    modifier = Modifier.size(96.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.chat_scan_device_hint, peerDeviceId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
internal fun SignalProtocol.IdentityTrustState.toLabel(): String = stringResource(when (this) {
    SignalProtocol.IdentityTrustState.UNKNOWN -> R.string.chat_trust_unknown
    SignalProtocol.IdentityTrustState.TRUSTED -> R.string.chat_trust_first
    SignalProtocol.IdentityTrustState.VERIFIED -> R.string.chat_verified
    SignalProtocol.IdentityTrustState.CHANGED -> R.string.chat_trust_changed
})
