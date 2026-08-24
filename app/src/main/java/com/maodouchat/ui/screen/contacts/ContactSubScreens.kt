@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.pluralStringResource
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
import com.maodouchat.ui.theme.MaodouchatTheme
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
    var scannedUserFriendBusy by remember { mutableStateOf(false) }
    var scannedUserFriendMessage by remember { mutableStateOf<String?>(null) }
    var joinedChat by remember { mutableStateOf<ChatDto?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var safetyScanResult by remember { mutableStateOf<SafetyScanResult?>(null) }
    var invalidQr by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    // 9.280：扫码结果处理统一入口（页内嵌入扫码/相册解码/旧 CaptureActivity 兼容三路共用）
    val handleScanResult: (String) -> Unit = handleRaw@{ raw ->
        val target = QrCodeGenerator.parsePayload(raw)
        invalidQr = false
        if (target == null) {
            scannedTarget = null
            scannedUser = null
            scannedUserError = null
            scannedUserFriendBusy = false
            scannedUserFriendMessage = null
            joinedChat = null
            inviteError = null
            safetyScanResult = null
            invalidQr = true
            return@handleRaw
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
                scannedUserFriendBusy = false
                scannedUserFriendMessage = null
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
                        if (token.isBlank() || scanOwnerUserId.isBlank()) {
                            if (cached == null) {
                                scannedUserError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            }
                            return@launch
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = scanOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            if (cached == null) {
                                scannedUserError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            }
                            return@launch
                        }
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
                                val api = error as? ApiException
                                scannedUserError = qrScanMessage(
                                    context,
                                    QrScanFeedbackPolicy.forUserLookup(
                                        httpStatus = api?.statusCode,
                                        isNetwork = api?.kind == ApiFailureKind.NETWORK,
                                        isTimeout = api?.kind == ApiFailureKind.TIMEOUT
                                    )
                                )
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
    
    // 旧 CaptureActivity 兑底路径（保留可用，主入口已改为页内嵌入扫码）
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        handleScanResult(raw)
    }
    // 9.280：相册图片 QR 解码（Photo Picker 免权限，zxing core 本地解码）
    var decodingGallery by remember { mutableStateOf(false) }
    val galleryQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodingGallery = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { decodeQrFromImage(context, uri) }
            decodingGallery = false
            if (text == null) {
                Toast.makeText(context, context.getString(R.string.contacts_gallery_qr_not_found), Toast.LENGTH_SHORT).show()
            } else {
                handleScanResult(text)
            }
        }
    }
    // 9.280：相机权限状态（页内预览需要）
    var cameraGranted by remember {
        mutableStateOf(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.contacts_scan), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        // 9.280：页内嵌入扫码器（替代第三方复古 CaptureActivity 页面），实时预览 + 自定义观感
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)) {
            if (cameraGranted) {
                val barcodeView = remember {
                    com.journeyapps.barcodescanner.DecoratedBarcodeView(context).apply {
                        setStatusText("")
                        barcodeView.decoderFactory = com.journeyapps.barcodescanner.DefaultDecoderFactory(listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                    }
                }
                val scanConsumed = remember { mutableStateOf(false) }
                DisposableEffect(barcodeView) {
                    barcodeView.decodeContinuous { result ->
                        if (!scanConsumed.value && !result.text.isNullOrBlank()) {
                            scanConsumed.value = true
                            barcodeView.pause()
                            handleScanResult(result.text)
                        }
                    }
                    barcodeView.resume()
                    onDispose { barcodeView.pause() }
                }
                // 结果弹窗关闭后恢复扫描
                LaunchedEffect(scannedTarget, invalidQr, loading) {
                    if (scannedTarget == null && !invalidQr && !loading) {
                        scanConsumed.value = false
                        barcodeView.resume()
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { barcodeView },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.contacts_camera_permission_needed), style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { cameraPermLauncher.launch(android.Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.contacts_camera_grant))
                    }
                }
            }
            if (decodingGallery) {
                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    
        // 底部操作区：从相册选择 + 传统扫描兑底
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { galleryQrLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.contacts_scan_from_gallery))
            }
            if (!cameraGranted) {
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
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.contacts_start_scan))
                }
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
        val alreadyFriend = remember(user.id) {
            com.maodouchat.data.repository.FriendCacheStore.getFriendIds(context).contains(user.id)
        }
        AlertDialog(
            onDismissRequest = { scannedTarget = null; scannedUser = null; scannedUserFriendMessage = null },
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
                    scannedUserFriendMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val ok = msg == stringResource(R.string.contacts_friend_request_sent)
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!alreadyFriend) {
                        TextButton(
                            enabled = !scannedUserFriendBusy,
                            onClick = {
                                if (!RuntimeFlags.isEnabled(context, RuntimeFlags.FRIEND_REQUESTS)) {
                                    scannedUserFriendMessage = context.getString(R.string.friend_requests_disabled)
                                    return@TextButton
                                }
                                val tokenManager = TokenManager.getInstance(context)
                                val token = tokenManager.getToken().orEmpty()
                                val ownerUserId = tokenManager.getUserId().orEmpty()
                                if (token.isBlank() || ownerUserId.isBlank()) {
                                    scannedUserFriendMessage = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                                    return@TextButton
                                }
                                scannedUserFriendBusy = true
                                scannedUserFriendMessage = null
                                scope.launch {
                                    try {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = ownerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            scannedUserFriendMessage = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                                            return@launch
                                        }
                                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                                        ApiService.sendFriendRequest(liveToken, user.id, "").fold(
                                            onSuccess = {
                                                scannedUserFriendMessage = context.getString(R.string.contacts_friend_request_sent)
                                            },
                                            onFailure = { error ->
                                                scannedUserFriendMessage = error.message
                                                    ?: context.getString(R.string.contacts_friend_request_failed)
                                            }
                                        )
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        scannedUserFriendMessage = error.message
                                            ?: context.getString(R.string.contacts_friend_request_failed)
                                    } finally {
                                        scannedUserFriendBusy = false
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.contacts_add_friend), color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = {
                        scannedTarget = null
                        onAddContact(user)
                    }) { Text(stringResource(R.string.contacts_start_chat), color = MaterialTheme.colorScheme.primary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedTarget = null; scannedUser = null; scannedUserFriendMessage = null }) {
                    Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                }
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
                    Text(pluralStringResource(R.plurals.chat_members_count, chat.participants.size, chat.participants.size), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
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

/**
 * 9.280：从相册图片解码 QR（zxing core，本地无需网络）。
 * 大图先降采样到 1600px 内避免 OOM；解码失败/无码返回 null。
 */
private fun decodeQrFromImage(context: android.content.Context, uri: Uri): String? = runCatching {
    val bitmap: Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(
            android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        ) { decoder, info, _ ->
            val maxSide = 1600
            if (info.size.width > maxSide || info.size.height > maxSide) {
                decoder.setTargetSize(maxSide, maxSide)
            }
            decoder.setMutableRequired(true)
        }
    } else {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        boundsStream.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        val maxSide = 1600
        var sample = 1
        while (options.outWidth / sample > maxSide || options.outHeight / sample > maxSide) sample *= 2
        val realOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        decodeStream.use { android.graphics.BitmapFactory.decodeStream(it, null, realOptions) }
            ?: return@runCatching null
    }
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
    val reader = com.google.zxing.MultiFormatReader().apply {
        setHints(mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }
    // 先 Hybrid 二值化解码，失败后换 GlobalHistogram 再试一次（截图/暗色背景兼容）
    runCatching { reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))) }
        .recoverCatching { reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source))) }
        .getOrThrow()
        .text
}.getOrNull()

private fun String.normalizedSafetyCode(): String = filter { it.isDigit() }
private fun String.normalizedFingerprint(): String = filter { it.isLetterOrDigit() }.lowercase()

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewScanScreen() {
    MaodouchatTheme { ScanScreen() }
}
