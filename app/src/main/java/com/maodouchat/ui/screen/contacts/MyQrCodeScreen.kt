@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 我的二维码页 — 展示当前用户 Maodouchat 号 + 头像 + 二维码。
 * 让别人扫码后加我。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrCodeScreen(
    onBack: () -> Unit = {},
    onOpenScan: () -> Unit = {},
    viewModel: MyQrCodeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shareIdChooserTitle = stringResource(R.string.contacts_share_id_chooser)
    val shareQrChooserTitle = stringResource(R.string.contacts_share_qr_chooser)
    val shareFailedMsg = stringResource(R.string.contacts_share_failed)
    val idCopiedMsg = stringResource(R.string.contacts_id_copied)
    val qrSavedMsg = stringResource(R.string.contacts_qr_saved)
    val qrSaveFailedMsg = stringResource(R.string.contacts_qr_save_failed)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.profile_my_qr), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
            },
            actions = {
                IconButton(onClick = { viewModel.reload() }, enabled = !state.isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onOpenScan) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.contacts_scan), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            Avatar(name = state.userName, avatarUrl = state.userAvatar, size = AvatarSize.LG)
            Spacer(modifier = Modifier.height(12.dp))
            Text(state.userName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.profile_maodou_id, state.userId), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                if (state.userId.isNotBlank()) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("maodouchat_id", state.userId)
                            )
                            Toast.makeText(context, idCopiedMsg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.contacts_copy_id),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))

            // 二维码卡片
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(20.dp)
            ) {
                if (state.qrBitmap != null) {
                    Image(
                        bitmap = state.qrBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_my_qr),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(260.dp)
                    )
                } else {
                    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                        Text(state.errorMessage ?: stringResource(R.string.contacts_qr_generation_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.contacts_my_qr_hint), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("maodouchat_id", state.userId)
                        )
                        Toast.makeText(context, idCopiedMsg, Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.userId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.contacts_copy_id))
                }
                OutlinedButton(
                    onClick = {
                        val bmp = state.qrBitmap ?: return@OutlinedButton
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.util.MediaExport.saveBitmapToGallery(
                                    context,
                                    bmp,
                                    "maodouchat-qr-${state.userId.take(12)}"
                                )
                            }
                            Toast.makeText(context, if (ok) qrSavedMsg else qrSaveFailedMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = state.qrBitmap != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.contacts_save_qr))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, QrCodeGenerator.encodeUserQrPayload(state.userId))
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, shareIdChooserTitle))
                        }.onFailure {
                            Toast.makeText(context, shareFailedMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = state.userId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.contacts_share_id))
                }
                OutlinedButton(
                    onClick = {
                        val bmp = state.qrBitmap ?: return@OutlinedButton
                        scope.launch {
                            val shared = withContext(Dispatchers.IO) {
                                runCatching {
                                    val cacheDir = java.io.File(context.cacheDir, "maodouchat_media").apply { mkdirs() }
                                    val file = java.io.File(cacheDir, "maodouchat-qr-${state.userId.take(12)}.png")
                                    java.io.FileOutputStream(file).use { out ->
                                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return@runCatching false
                                    }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, shareQrChooserTitle))
                                    true
                                }.getOrDefault(false)
                            }
                            if (!shared) {
                                Toast.makeText(context, shareFailedMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = state.qrBitmap != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.QrCode, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.contacts_share_qr))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onOpenScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.contacts_scan))
            }

            state.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
