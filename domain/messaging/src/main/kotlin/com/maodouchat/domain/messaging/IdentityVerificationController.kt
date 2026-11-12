package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.IdentitySafetyPolicy
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.DeviceSafetyState
import com.maodouchat.core.model.WarningKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 「安全码 / 身份核验」控制器：对方设备安全状态加载、身份信任标记、一键验证。
// Context/SignalProtocol/CurrentSession/会话门禁/开关/R 文案走端口，其余逻辑逐字平移。
class IdentityVerificationController(
    private val scope: CoroutineScope,
    private val resetDirectIdentityForGroup: (ChatDetailUiState) -> ChatDetailUiState,
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val text: (IdentityVerificationText) -> String,
    private val currentUserId: () -> String,
    private val sessionToken: () -> String?,
    private val sessionMayContinue: (String) -> Boolean,
    private val safetyCodeEnabled: () -> Boolean,
    private val deviceVerifyEnabled: () -> Boolean,
    private val markFingerprintVerified: (String) -> Unit,
    private val getRemoteDeviceSafetyStates: suspend (String, String) -> Result<List<DeviceSafetyState>>,
    private val markIdentityVerified: (String, Int) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun refreshIdentitySafetyState(contactId: String): Job? {
        if (uiState.value.chat?.isGroup == true) {
            uiState.update(resetDirectIdentityForGroup)
            return null
        }
        if (contactId.isBlank()) return null
        val safetyOwnerUserId = currentUserId()
        if (
            safetyOwnerUserId.isBlank() ||
            safetyOwnerUserId == "me" ||
            !sessionMayContinue(safetyOwnerUserId)
        ) {
            return null
        }
        return scope.launch {
            uiState.update { it.copy(isLoadingDeviceSafety = true, deviceSafetyWarning = null) }
            try {
                if (!sessionMayContinue(safetyOwnerUserId)) {
                    uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                val liveToken = sessionToken().orEmpty()
                val result = withContext(ioDispatcher) {
                    getRemoteDeviceSafetyStates(liveToken, contactId)
                }
                if (!sessionMayContinue(safetyOwnerUserId)) {
                    uiState.update { it.copy(isLoadingDeviceSafety = false) }
                    return@launch
                }
                result.fold(
                    onSuccess = { states ->
                        if (!sessionMayContinue(safetyOwnerUserId)) {
                            return@fold
                        }
                        val aggregateTrust = IdentitySafetyPolicy.aggregateTrust(states)
                        val primary = IdentitySafetyPolicy.primaryDevice(states)
                        val warning = when (IdentitySafetyPolicy.warningKind(aggregateTrust)) {
                            WarningKind.CHANGED ->
                                text(IdentityVerificationText.IDENTITY_CHANGED_WARNING)
                            WarningKind.UNKNOWN ->
                                text(IdentityVerificationText.IDENTITY_UNKNOWN_WARNING)
                            WarningKind.TRUSTED ->
                                text(IdentityVerificationText.IDENTITY_TRUSTED_WARNING)
                            WarningKind.NONE -> null
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
                        if (!sessionMayContinue(safetyOwnerUserId)) {
                            return@fold
                        }
                        uiState.update { state ->
                            state.copy(
                                isLoadingDeviceSafety = false,
                                deviceSafetyWarning = text(IdentityVerificationText.SAFETY_DEVICES_LOAD_FAILED)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                uiState.update { it.copy(isLoadingDeviceSafety = false) }
                throw error
            }
        }
    }

    fun showSafetyCodeDialog() {
        if (!safetyCodeEnabled()) {
            uiState.update { it.copy(errorMessage = text(IdentityVerificationText.SAFETY_CODE_DISABLED)) }
            return
        }
        uiState.update { it.copy(showSafetyCodeDialog = true) }
        refreshIdentitySafetyState(uiState.value.contact.id)
    }

    fun dismissSafetyCodeDialog() { uiState.update { it.copy(showSafetyCodeDialog = false) } }

    fun verifyAndTrustIdentity(deviceId: Int? = null) {
        val contactId = uiState.value.contact.id
        if (contactId.isBlank()) return
        val verifyOwnerUserId = currentUserId()
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !sessionMayContinue(verifyOwnerUserId)
        ) {
            return
        }
        val targetDeviceId = deviceId ?: 1
        scope.launch {
            if (!sessionMayContinue(verifyOwnerUserId)) {
                return@launch
            }
            val success = withContext(ioDispatcher) { markIdentityVerified(contactId, targetDeviceId) }
            if (!sessionMayContinue(verifyOwnerUserId)) {
                return@launch
            }
            if (success) {
                // 先等刷新跑完再读指纹：刷新是异步启动的，直接读会拿到刷新前的旧指纹
                //（首次核验时为 null，指纹就永远不同步，下次进入还会再弹核验页）。
                refreshIdentitySafetyState(contactId)?.join()
                // 设备核验（dvz）：服务端确认后同步本地已核验指纹。
                if (deviceVerifyEnabled()) {
                    val fp = uiState.value.contactIdentityFingerprint?.takeIf { it.isNotBlank() }
                    if (fp != null) markFingerprintVerified(fp)
                }
            } else {
                uiState.update { it.copy(deviceSafetyWarning = text(IdentityVerificationText.TRUST_FAILED)) }
            }
        }
    }

    /** 一键验证对方所有设备：逐设备调用 markIdentityVerified，适合 QR 扫码批量核验后使用。 */
    fun verifyAllDevices() {
        val contactId = uiState.value.contact.id
        if (contactId.isBlank()) return
        val devices = uiState.value.deviceSafetyStates
        if (devices.isEmpty()) return
        val verifyOwnerUserId = currentUserId()
        if (
            verifyOwnerUserId.isBlank() ||
            verifyOwnerUserId == "me" ||
            !sessionMayContinue(verifyOwnerUserId)
        ) {
            return
        }
        scope.launch {
            var allSuccess = true
            for (device in devices) {
                if (!sessionMayContinue(verifyOwnerUserId)) {
                    return@launch
                }
                val ok = withContext(ioDispatcher) {
                    markIdentityVerified(contactId, device.deviceId)
                }
                if (!ok) allSuccess = false
            }
            if (!sessionMayContinue(verifyOwnerUserId)) {
                return@launch
            }
            if (allSuccess) refreshIdentitySafetyState(contactId)
            else uiState.update { it.copy(deviceSafetyWarning = text(IdentityVerificationText.TRUST_FAILED)) }
        }
    }
}
