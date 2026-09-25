package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import com.maodouchat.R
import com.maodouchat.crypto.IdentitySafetyPolicy
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.SecretDeviceVerifyPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「安全码 / 身份核验」控制器。把对方设备安全状态加载、身份信任标记、一键验证等
 * 逻辑从 ChatDetailViewModel 抽出；只依赖注入的 scope/token/signal/state，不依赖 ViewModel。
 */
class IdentityVerificationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tokenManager: TokenManager,
    private val signalProtocol: SignalProtocol,
    private val resetDirectIdentityForGroup: (ChatDetailUiState) -> ChatDetailUiState,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val text: (Int) -> String,
) {
    private val currentUserId: String get() = tokenManager.getUserId() ?: "me"
    private val token: String get() = tokenManager.getToken() ?: ""

    fun refreshIdentitySafetyState(contactId: String) {
        if (uiState.value.chat?.isGroup == true) {
            uiState.update(resetDirectIdentityForGroup)
            return
        }
        if (contactId.isBlank()) return
        val safetyOwnerUserId = currentUserId
        if (
            safetyOwnerUserId.isBlank() ||
            safetyOwnerUserId == "me" ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = safetyOwnerUserId,
            )
        ) {
            return
        }
        scope.launch {
            uiState.update { it.copy(isLoadingDeviceSafety = true, deviceSafetyWarning = null) }
            try {
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = safetyOwnerUserId,
                )
                ) {
                    uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = withContext(Dispatchers.IO) {
                    signalProtocol.getRemoteDeviceSafetyStates(liveToken, contactId)
                }
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = safetyOwnerUserId,
                )
                ) {
                    uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                result.fold(
                    onSuccess = { states ->
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = safetyOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        val aggregateTrust = IdentitySafetyPolicy.aggregateTrust(states)
                        val primary = IdentitySafetyPolicy.primaryDevice(states)
                        val warning = when (IdentitySafetyPolicy.warningKind(aggregateTrust)) {
                            IdentitySafetyPolicy.WarningKind.CHANGED ->
                                text(R.string.chat_identity_changed_warning)
                            IdentitySafetyPolicy.WarningKind.UNKNOWN ->
                                text(R.string.chat_identity_unknown_warning)
                            IdentitySafetyPolicy.WarningKind.TRUSTED ->
                                text(R.string.chat_identity_trusted_warning)
                            IdentitySafetyPolicy.WarningKind.NONE -> null
                        }
                        uiState.update {
                            it.copy(
                                identityWarning = warning,
                                safetyCode = primary?.safetyCode,
                                contactIdentityFingerprint = primary?.identityFingerprint,
                                trustState = aggregateTrust,
                                deviceSafetyStates = states,
                                isLoadingDeviceSafety = false,
                                deviceSafetyWarning = null,
                                canVerifyIdentity = IdentitySafetyPolicy.canVerifyAny(states)
                            )
                        }
                    },
                    onFailure = {
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = safetyOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        uiState.update { state ->
                            state.copy(
                                isLoadingDeviceSafety = false,
                                deviceSafetyWarning = text(R.string.chat_safety_devices_load_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                uiState.update { it.copy(isLoadingDeviceSafety = false) }
                throw error
            }
        }
    }

    fun showSafetyCodeDialog() {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) {
            uiState.update { it.copy(errorMessage = text(R.string.safety_code_disabled)) }
            return
        }
        uiState.update { it.copy(showSafetyCodeDialog = true) }
        refreshIdentitySafetyState(uiState.value.contact.id)
    }

    fun dismissSafetyCodeDialog() { uiState.update { it.copy(showSafetyCodeDialog = false) } }

    fun verifyAndTrustIdentity(deviceId: Int? = null) {
        val contactId = uiState.value.contact.id
        if (contactId.isBlank()) return
        val verifyOwnerUserId = currentUserId
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
            )
        ) {
            return
        }
        val targetDeviceId = deviceId ?: 1
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
            )
            ) {
                return@launch
            }
            val success = withContext(Dispatchers.IO) { signalProtocol.markIdentityVerified(contactId, targetDeviceId) }
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
            )
            ) {
                return@launch
            }
            if (success) {
                refreshIdentitySafetyState(contactId)
                // 设备核验（dvz）：服务端确认后同步本地已核验指纹，避免下次进入再次弹核验页
                if (SecretDeviceVerifyPrefs.isEnabled(context)) {
                    val fp = uiState.value.contactIdentityFingerprint?.takeIf { it.isNotBlank() }
                    if (fp != null) SecretDeviceVerifyPrefs.markFingerprintVerified(context, fp)
                }
            } else {
                uiState.update { it.copy(deviceSafetyWarning = text(R.string.contacts_safety_trust_failed)) }
            }
        }
    }

    /** 一键验证对方所有设备：逐设备调用 markIdentityVerified，适合 QR 扫码批量核验后使用。 */
    fun verifyAllDevices() {
        val contactId = uiState.value.contact.id
        if (contactId.isBlank()) return
        val devices = uiState.value.deviceSafetyStates
        if (devices.isEmpty()) return
        val verifyOwnerUserId = currentUserId
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
            )
        ) {
            return
        }
        scope.launch {
            var allSuccess = true
            for (device in devices) {
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = verifyOwnerUserId,
                )
                ) {
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) {
                    signalProtocol.markIdentityVerified(contactId, device.deviceId)
                }
                if (!ok) allSuccess = false
            }
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = verifyOwnerUserId,
            )
            ) {
                return@launch
            }
            if (allSuccess) refreshIdentitySafetyState(contactId)
            else uiState.update { it.copy(deviceSafetyWarning = text(R.string.contacts_safety_trust_failed)) }
        }
    }
}
