@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.maodouchat.data.model.User
import com.maodouchat.ui.theme.MaodouchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫一扫页 — 调用系统级 ZXing CaptureActivity 扫描，解析后：
 *  - "maodouchat:user:<id>" → 显示对方资料弹窗，提供"加好友"
 *  - "maodouchat:chat:<id>" → 跳到对应聊天
 *
 * 状态与结果处理已拆入 [ScanScreenUiState]，结果弹窗拆入 [ScanResultDialogs]；
 * 本文件只留页面骨架（扫码器 UI、底部操作区、launcher 接线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun ScanScreen(
    onBack: () -> Unit = {},
    onAddContact: (User) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onJoinGroupInvite: (inviteCode: String) -> Unit = {},
) {
    val context = LocalContext.current
    if (!RuntimeFlags.isEnabled(context, RuntimeFlags.QR_CODE)) {
        AlertDialog(
            onDismissRequest = onBack,
            confirmButton = {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.common_back))
                }
            },
            title = { Text(stringResource(R.string.contacts_scan_align)) },
            text = { Text(stringResource(R.string.qr_code_disabled)) }
        )
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scanAlignPrompt = stringResource(R.string.contacts_scan_align)
    val state = remember {
        ScanScreenUiState(
            context = context,
            scope = scope,
            onBack = onBack,
            onAddContact = onAddContact,
            onOpenChat = onOpenChat,
            onJoinGroupInvite = onJoinGroupInvite,
            safetyTrustedMsg = context.getString(R.string.contacts_safety_trusted),
            safetyTrustFailedMsg = context.getString(R.string.contacts_safety_trust_failed),
        )
    }

    // 旧 CaptureActivity 兑底路径（保留可用，主入口已改为页内嵌入扫码）
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        state.handleScanResult(raw)
    }
    // 相册图片 QR 解码（Photo Picker 免权限，zxing core 本地解码）
    var decodingGallery by remember { mutableStateOf(false) }
    val galleryQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodingGallery = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { state.decodeQrFromImage(context, uri) }
            decodingGallery = false
            if (text == null) {
                Toast.makeText(context, context.getString(R.string.contacts_gallery_qr_not_found), Toast.LENGTH_SHORT).show()
            } else {
                state.handleScanResult(text)
            }
        }
    }
    // 相机权限状态（页内预览需要）
    var cameraGranted by remember {
        mutableStateOf(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.contacts_scan), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        // 页内嵌入扫码器（替代第三方复古 CaptureActivity 页面），实时预览 + 自定义观感
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
                            state.handleScanResult(result.text)
                        }
                    }
                    barcodeView.resume()
                    onDispose { barcodeView.pause() }
                }
                // 结果弹窗关闭后恢复扫描
                LaunchedEffect(state.scannedTarget, state.invalidQr, state.loading) {
                    if (state.scannedTarget == null && !state.invalidQr && !state.loading) {
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

    ScanResultDialogs(state)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewScanScreen() {
    MaodouchatTheme { ScanScreen() }
}
