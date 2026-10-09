@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.security.SafetyScanStatus
import com.maodouchat.util.QrCodeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SafetyScanResult(
    val target: QrCodeGenerator.QrTarget.Safety,
    val status: SafetyScanStatus
) {
    val matched: Boolean
        get() = status == SafetyScanStatus.FINGERPRINT_MATCH || status == SafetyScanStatus.CODE_MATCH
}

/**
 * 扫一扫页的可变状态与结果处理：从 ScanScreen 拆出的非 UI 簇。
 * 所有 state 原先是 ScanScreen 内的 remember 局部变量，语义不变（remember 一次、读写触发重组）。
 */
internal class ScanScreenUiState(
    val context: Context,
    val scope: CoroutineScope,
    val onBack: () -> Unit,
    val onAddContact: (User) -> Unit,
    val onOpenChat: (String) -> Unit,
    val onJoinGroupInvite: (inviteCode: String) -> Unit,
    val safetyTrustedMsg: String,
    val safetyTrustFailedMsg: String,
) {
    var scannedTarget by mutableStateOf<QrCodeGenerator.QrTarget?>(null)
    var scannedUser by mutableStateOf<User?>(null)
    var scannedUserError by mutableStateOf<String?>(null)
    var scannedUserFriendBusy by mutableStateOf(false)
    var scannedUserFriendMessage by mutableStateOf<String?>(null)
    var safetyScanResult by mutableStateOf<SafetyScanResult?>(null)
    var invalidQr by mutableStateOf(false)
    var loading by mutableStateOf(false)

    // 扫码结果统一入口：页内嵌入扫码 / 相册解码 / 旧 CaptureActivity 三路共用。
    // 原先是 ScanScreen 内的 lambda，捕获的 state 全部收进本类，逻辑逐字保留。
    fun handleScanResult(raw: String) {
        val target = QrCodeGenerator.parsePayload(raw)
        invalidQr = false
        if (target == null) {
            scannedTarget = null
            scannedUser = null
            scannedUserError = null
            scannedUserFriendBusy = false
            scannedUserFriendMessage = null
            safetyScanResult = null
            invalidQr = true
            return
        }
        when (target) {
            is QrCodeGenerator.QrTarget.Chat -> onOpenChat(target.chatId)
            is QrCodeGenerator.QrTarget.ChatInvite -> {
                // QR 邀请与深链统一经 JoinGroupInvite 路由，不再页内直调 join API。
                onJoinGroupInvite(target.token)
            }
            is QrCodeGenerator.QrTarget.Safety -> {
                scannedTarget = target
                loading = true
                scope.launch {
                    try {
                        // 核验决策收进非 ui 的 QrSafetyScanEvaluator（含 app 单例/信号访问），
                        // 本处只做「QrTarget → 请求」「状态 → 结果」的映射。
                        val currentUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                        val result = withContext(Dispatchers.IO) {
                            val status = com.maodouchat.security.QrSafetyScanEvaluator.evaluate(
                                request = com.maodouchat.security.SafetyScanRequest(
                                    ownerUserId = target.ownerUserId,
                                    ownerDeviceId = target.ownerDeviceId,
                                    peerUserId = target.peerUserId,
                                    peerDeviceId = target.peerDeviceId,
                                    ownerIdentityFingerprint = target.ownerIdentityFingerprint,
                                    peerIdentityFingerprint = target.peerIdentityFingerprint,
                                    safetyCode = target.safetyCode,
                                ),
                                currentUserId = currentUserId,
                                signal = com.maodouchat.security.SignalIdentityAccess,
                            )
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
                // 解析对方资料（用页面的 CoroutineScope，离开页面会自动取消）
                val scanOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                scope.launch {
                    try {
                        val userRepo = com.maodouchat.data.repository.AppRepositories.users
                        val cached = userRepo.getUserById(target.userId)
                        if (cached != null) scannedUser = cached
                        if (!com.maodouchat.session.CurrentSession.hasSession()) {
                            if (cached == null) {
                                scannedUserError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            }
                            return@launch
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = scanOwnerUserId,
                        )
                        ) {
                            if (cached == null) {
                                scannedUserError = qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                            }
                            return@launch
                        }
                        // 按 id 定向查询：全量 getUsers() 会把「有效用户码」误判为「查不到用户」，
                        // 且无法区分网络错误。
                        com.maodouchat.data.repository.UserNetworkRepository().user(userId = target.userId).onSuccess { dto ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = scanOwnerUserId,
                            )
                            ) {
                                return@onSuccess
                            }
                            val u = User(dto.id, dto.name, dto.avatar, dto.email, dto.isOnline, dto.status, lastSeen = dto.lastSeen)
                            scannedUser = u
                            userRepo.insertUsers(listOf(u))
                        }.onFailure { error ->
                            // 网络错误保留缓存，不覆盖为「查不到用户」；无缓存时给出具体错误。
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

    /** 安全码核验结果文案。 */
    @Composable
    // 资源字符串均在回调/协程内读取，非组合作用域
    @SuppressLint("LocalContextGetResourceValueCall")
    fun safetyScanMessage(result: SafetyScanResult): String = when (result.status) {
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

    /** 扫码反馈策略 → 用户可读文案。 */
    fun qrScanMessage(context: Context, feedback: QrScanFeedbackPolicy.Feedback): String =
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
     * 从相册图片解码 QR（zxing core，本地无需网络）。
     * 大图先降采样到 1600px 内避免 OOM；解码失败/无码返回 null。
     */
    fun decodeQrFromImage(context: Context, uri: Uri): String? = runCatching {
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
}
