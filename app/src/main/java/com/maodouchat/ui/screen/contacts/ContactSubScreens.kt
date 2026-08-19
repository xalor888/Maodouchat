@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.ui.theme.LocalChatPalette

private data class SafetyScanResult(
    val target: QrCodeGenerator.QrTarget.Safety,
    val status: SafetyScanStatus
) {
    val matched: Boolean
        get() = status == SafetyScanStatus.FINGERPRINT_MATCH || status == SafetyScanStatus.CODE_MATCH
}

private enum class SafetyScanStatus {
    SESSION_EXPIRED,
    WRONG_ACCOUNT,
    WRONG_DEVICE,
    NO_SESSION,
    WRONG_DEVICE_QR,
    FINGERPRINT_MATCH,
    FINGERPRINT_MISMATCH,
    INVALID_CODE,
    CODE_MATCH,
    CODE_MISMATCH
}

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
            title = { Text(stringResource(R.string.profile_my_qr), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            actions = {
                IconButton(onClick = { viewModel.reload() }, enabled = !state.isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onOpenScan) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.contacts_scan), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
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
                        Text(state.errorMessage ?: stringResource(R.string.contacts_qr_generation_failed), color = Error)
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
                Text(msg, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 扫一扫页 — 调用系统级 ZXing CaptureActivity 扫描，解析后：
 *  - "maodouchat:user:<id>" → 显示对方资料弹窗，提供"加好友"
 *  - "maodouchat:chat:<id>" → 跳到对应聊天
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun ScanScreen(
    onBack: () -> Unit = {},
    onAddContact: (User) -> Unit = {},
    onOpenChat: (String) -> Unit = {}
) {
    val context = LocalContext.current
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.QR_CODE)) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBack,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onBack) {
                    androidx.compose.material3.Text(stringResource(R.string.common_back))
                }
            },
            title = { androidx.compose.material3.Text(stringResource(R.string.contacts_scan_align)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.qr_code_disabled)) }
        )
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scanAlignPrompt = stringResource(R.string.contacts_scan_align)
    val safetyTrustedMsg = stringResource(R.string.contacts_safety_trusted)
    val safetyTrustFailedMsg = stringResource(R.string.contacts_safety_trust_failed)
    var scannedTarget by remember { mutableStateOf<QrCodeGenerator.QrTarget?>(null) }
    var scannedUser by remember { mutableStateOf<User?>(null) }
    var scannedUserError by remember { mutableStateOf<String?>(null) }
    var joinedChat by remember { mutableStateOf<ChatDto?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var safetyScanResult by remember { mutableStateOf<SafetyScanResult?>(null) }
    var invalidQr by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val target = QrCodeGenerator.parsePayload(raw)
        invalidQr = false
        if (target == null) {
            scannedTarget = null
            scannedUser = null
            joinedChat = null
            inviteError = null
            safetyScanResult = null
            invalidQr = true
            return@rememberLauncherForActivityResult
        }
        when (target) {
            is QrCodeGenerator.QrTarget.Chat -> onOpenChat(target.chatId)
            is QrCodeGenerator.QrTarget.ChatInvite -> {
                scannedTarget = target
                joinedChat = null
                inviteError = null
                loading = true
                val tokenManager = TokenManager.getInstance(context)
                val token = tokenManager.getToken().orEmpty()
                val joinOwnerUserId = tokenManager.getUserId().orEmpty()
                scope.launch {
                    try {
                        if (token.isBlank() || joinOwnerUserId.isBlank()) {
                            inviteError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            return@launch
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = joinOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            inviteError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            return@launch
                        }
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        ApiService.joinGroupByInvite(liveToken, target.token)
                            .onSuccess { chat ->
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = joinOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@onSuccess
                                }
                                joinedChat = chat
                            }
                            .onFailure { error ->
                                val api = error as? ApiException
                                val feedback = QrScanFeedbackPolicy.forJoinInvite(
                                    httpStatus = api?.statusCode,
                                    serverCode = api?.serverCode,
                                    serverMessage = api?.serverMessage ?: error.message,
                                    isNetwork = api?.kind == ApiFailureKind.NETWORK,
                                    isTimeout = api?.kind == ApiFailureKind.TIMEOUT
                                )
                                inviteError = qrScanMessage(context, feedback)
                            }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        loading = false
                        throw error
                    } finally {
                        loading = false
                    }
                }
            }
            is QrCodeGenerator.QrTarget.Safety -> {
                scannedTarget = target
                loading = true
                scope.launch {
                    try {
                        val app = context.applicationContext as com.maodouchat.MaodouchatApp
                        val currentUserId = TokenManager.getInstance(context).getUserId().orEmpty()
                        val currentDeviceId = app.signalProtocol.getDeviceId()
                        val result = withContext(Dispatchers.IO) {
                            val status = when {
                                currentUserId.isBlank() -> SafetyScanStatus.SESSION_EXPIRED
                                target.peerUserId != currentUserId -> SafetyScanStatus.WRONG_ACCOUNT
                                target.peerDeviceId != currentDeviceId -> SafetyScanStatus.WRONG_DEVICE
                                else -> {
                                    val ownerFingerprint = target.ownerIdentityFingerprint
                                    val peerFingerprint = target.peerIdentityFingerprint
                                    if (!ownerFingerprint.isNullOrBlank() && !peerFingerprint.isNullOrBlank()) {
                                        val localPeerFingerprint = app.signalProtocol.getLocalIdentityFingerprint()
                                        val localOwnerFingerprint = app.signalProtocol.getRemoteIdentityFingerprint(target.ownerUserId, target.ownerDeviceId)
                                        when {
                                            localOwnerFingerprint.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                                            localPeerFingerprint.normalizedFingerprint() != peerFingerprint.normalizedFingerprint() -> SafetyScanStatus.WRONG_DEVICE_QR
                                            localOwnerFingerprint.normalizedFingerprint() == ownerFingerprint.normalizedFingerprint() -> SafetyScanStatus.FINGERPRINT_MATCH
                                            else -> SafetyScanStatus.FINGERPRINT_MISMATCH
                                        }
                                    } else {
                                        val localCode = app.signalProtocol.getSafetyCode(target.ownerUserId, target.ownerDeviceId)
                                        when {
                                            localCode.isNullOrBlank() -> SafetyScanStatus.NO_SESSION
                                            target.safetyCode.isNullOrBlank() -> SafetyScanStatus.INVALID_CODE
                                            localCode.normalizedSafetyCode() == target.safetyCode.normalizedSafetyCode() -> SafetyScanStatus.CODE_MATCH
                                            else -> SafetyScanStatus.CODE_MISMATCH
                                        }
                                    }
                                }
                            }
                            SafetyScanResult(target, status)
                        }
                        safetyScanResult = result
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        loading = false
                        throw error
                    } finally {
                        loading = false
                    }
                }
            }
            is QrCodeGenerator.QrTarget.User -> {
                scannedTarget = target
                scannedUserError = null
                loading = true
                // 解析对方资料（用 Composable 内的 CoroutineScope，离开页面会自动取消）
                val tokenManager = TokenManager.getInstance(context)
                val token = tokenManager.getToken().orEmpty()
                val scanOwnerUserId = tokenManager.getUserId().orEmpty()
                scope.launch {
                    try {
                        val app = context.applicationContext as com.maodouchat.MaodouchatApp
                        val userRepo = com.maodouchat.data.repository.UserRepository(app.database.userDao())
                        val cached = userRepo.getUserById(target.userId)
                        if (cached != null) scannedUser = cached
                        if (token.isNotBlank() && scanOwnerUserId.isNotBlank() &&
                            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = scanOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                            // 8.38：改用按 id 定向查询——此前全量 getUsers() 在非好友/网络失败时
                            // 会把「有效用户码」误判为「查不到用户」，且无法区分网络错误
                            ApiService.getUser(liveToken, target.userId).onSuccess { dto ->
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = scanOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@onSuccess
                                }
                                val u = User(dto.id, dto.name, dto.avatar, dto.email, dto.isOnline, dto.status, lastSeen = dto.lastSeen)
                                scannedUser = u
                                userRepo.insertUsers(listOf(u))
                            }.onFailure { error ->
                                // 8.38：网络错误保留缓存，不覆盖为「查不到用户」；无缓存时给出具体错误
                                if (cached == null) {
                                    scannedUserError = error.message ?: context.getString(R.string.contacts_user_not_found)
                                }
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        loading = false
                        throw error
                    } finally {
                        loading = false
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.contacts_scan), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.contacts_scan_align), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.contacts_scan_supports), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt(scanAlignPrompt)
                        setBeepEnabled(true)
                        setOrientationLocked(false)
                        setBarcodeImageEnabled(false)
                    }
                    scanLauncher.launch(options)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.contacts_start_scan))
            }
        }
    }

    if (invalidQr) {
        AlertDialog(
            onDismissRequest = { invalidQr = false },
            title = { Text(stringResource(R.string.contacts_invalid_qr_title)) },
            text = { Text(stringResource(R.string.contacts_invalid_qr_message)) },
            confirmButton = {
                TextButton(onClick = { invalidQr = false }) { Text(stringResource(R.string.chat_acknowledge)) }
            }
        )
    }

    // 扫描结果弹窗
    val user = scannedUser
    if (scannedTarget is QrCodeGenerator.QrTarget.User && user != null) {
        AlertDialog(
            onDismissRequest = { scannedTarget = null; scannedUser = null },
            title = { Text(stringResource(R.string.contacts_found)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.LG, isOnline = user.isOnline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(user.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (user.status.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(user.status, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(user.id, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scannedTarget = null
                    onAddContact(user)
                }) { Text(stringResource(R.string.contacts_start_chat), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { scannedTarget = null; scannedUser = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
        )
    } else if (scannedTarget is QrCodeGenerator.QrTarget.User && loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.contacts_parsing)) },
            text = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {}
        )
    } else if (scannedTarget is QrCodeGenerator.QrTarget.User) {
        // 查不到用户（或网络失败且无本地缓存）
        AlertDialog(
            onDismissRequest = { scannedTarget = null },
            title = { Text(stringResource(R.string.contacts_user_not_found)) },
            text = {
                Text(
                    scannedUserError?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.contacts_user_not_found_hint)
                )
            },
            confirmButton = { TextButton(onClick = { scannedTarget = null }) { Text(stringResource(R.string.chat_acknowledge)) } }
        )
    }

    if (scannedTarget is QrCodeGenerator.QrTarget.ChatInvite && loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.contacts_joining_group)) },
            text = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {}
        )
    } else if (scannedTarget is QrCodeGenerator.QrTarget.ChatInvite && joinedChat != null) {
        val chat = joinedChat!!
        AlertDialog(
            onDismissRequest = { scannedTarget = null; joinedChat = null },
            title = { Text(stringResource(R.string.contacts_joined_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chat.groupName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_group))
                    Text(stringResource(R.string.chat_members_count, chat.participants.size), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scannedTarget = null
                    joinedChat = null
                    onOpenChat(chat.id)
                }) { Text(stringResource(R.string.contacts_enter_group), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { scannedTarget = null; joinedChat = null }) { Text(stringResource(R.string.chat_later), color = LocalChatPalette.current.textSecondary) }
            }
        )
    } else if (scannedTarget is QrCodeGenerator.QrTarget.ChatInvite && inviteError != null) {
        AlertDialog(
            onDismissRequest = { scannedTarget = null; inviteError = null },
            title = { Text(stringResource(R.string.contacts_cannot_join_group)) },
            text = { Text(inviteError.orEmpty()) },
            confirmButton = { TextButton(onClick = { scannedTarget = null; inviteError = null }) { Text(stringResource(R.string.chat_acknowledge)) } }
        )
    }

    if (safetyScanResult != null) {
        val result = safetyScanResult!!
        AlertDialog(
            onDismissRequest = { safetyScanResult = null; scannedTarget = null },
            title = { Text(if (result.matched) stringResource(R.string.contacts_safety_verified) else stringResource(R.string.contacts_safety_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(safetyScanMessage(result))
                    Text(
                        stringResource(R.string.contacts_safety_peer_device, result.target.ownerUserId, result.target.ownerDeviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            },
            confirmButton = {
                if (result.matched) {
                    TextButton(onClick = {
                        val app = context.applicationContext as com.maodouchat.MaodouchatApp
                        val ownerUserId = result.target.ownerUserId
                        val ownerDeviceId = result.target.ownerDeviceId
                        // markIdentityVerified 内部调用阻塞式 Room 查询（identityTrustDao.getTrustBlocking/upsertTrustBlocking），
                        // 必须在 IO 线程执行，避免主线程磁盘 I/O 导致 UI 卡顿/ANR
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                app.signalProtocol.markIdentityVerified(ownerUserId, ownerDeviceId)
                            }
                            Toast.makeText(
                                context,
                                if (ok) safetyTrustedMsg else safetyTrustFailedMsg,
                                Toast.LENGTH_SHORT
                            ).show()
                            safetyScanResult = null
                            scannedTarget = null
                        }
                    }) { Text(stringResource(R.string.contacts_safety_mark_trusted), color = MaterialTheme.colorScheme.primary) }
                } else {
                    TextButton(onClick = { safetyScanResult = null; scannedTarget = null }) { Text(stringResource(R.string.chat_acknowledge)) }
                }
            },
            dismissButton = {
                if (result.matched) {
                    TextButton(onClick = { safetyScanResult = null; scannedTarget = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
                }
            }
        )
    } else if (scannedTarget is QrCodeGenerator.QrTarget.Safety && loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.contacts_safety_verifying)) },
            text = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {}
        )
    }
}

@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
private fun safetyScanMessage(result: SafetyScanResult): String = when (result.status) {
    SafetyScanStatus.SESSION_EXPIRED -> stringResource(R.string.error_session_expired)
    SafetyScanStatus.WRONG_ACCOUNT -> stringResource(R.string.contacts_safety_wrong_account)
    SafetyScanStatus.WRONG_DEVICE -> stringResource(R.string.contacts_safety_use_device, result.target.peerDeviceId)
    SafetyScanStatus.NO_SESSION -> stringResource(R.string.contacts_safety_no_session)
    SafetyScanStatus.WRONG_DEVICE_QR -> stringResource(R.string.contacts_safety_wrong_device_qr)
    SafetyScanStatus.FINGERPRINT_MATCH -> stringResource(R.string.contacts_safety_fingerprint_match, result.target.ownerDeviceId)
    SafetyScanStatus.FINGERPRINT_MISMATCH -> stringResource(R.string.contacts_safety_fingerprint_mismatch)
    SafetyScanStatus.INVALID_CODE -> stringResource(R.string.contacts_safety_invalid_code)
    SafetyScanStatus.CODE_MATCH -> stringResource(R.string.contacts_safety_code_match, result.target.ownerDeviceId)
    SafetyScanStatus.CODE_MISMATCH -> stringResource(R.string.contacts_safety_code_mismatch)
}

private fun qrScanMessage(context: android.content.Context, feedback: QrScanFeedbackPolicy.Feedback): String =
    when (feedback.kind) {
        QrScanFeedbackPolicy.Kind.INVALID_PAYLOAD -> context.getString(R.string.contacts_invalid_qr_message)
        QrScanFeedbackPolicy.Kind.SESSION_EXPIRED -> context.getString(R.string.error_session_expired)
        QrScanFeedbackPolicy.Kind.USER_NOT_FOUND -> context.getString(R.string.contacts_user_not_found_hint)
        QrScanFeedbackPolicy.Kind.INVITE_INVALID_OR_EXPIRED -> context.getString(R.string.contacts_invite_invalid_or_expired)
        QrScanFeedbackPolicy.Kind.INVITE_BLOCKED -> context.getString(R.string.contacts_invite_blocked)
        QrScanFeedbackPolicy.Kind.GROUP_FULL -> context.getString(R.string.contacts_invite_group_full)
        QrScanFeedbackPolicy.Kind.NETWORK -> context.getString(R.string.contacts_invite_network)
        QrScanFeedbackPolicy.Kind.UNKNOWN -> context.getString(R.string.contacts_join_group_failed)
    }

private fun String.normalizedSafetyCode(): String = filter { it.isDigit() }
private fun String.normalizedFingerprint(): String = filter { it.isLetterOrDigit() }.lowercase()

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewScanScreen() {
    MaodouchatTheme { ScanScreen() }
}
