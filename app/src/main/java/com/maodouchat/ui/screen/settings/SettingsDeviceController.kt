package com.maodouchat.ui.screen.settings

import com.maodouchat.R
import com.maodouchat.network.DeviceInfoDto
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.settings.model.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * G356：「我的设备」管理（加载 / 移除 / 重命名 / 确认）从 `SettingsViewModel` 抽出
 * （纯搬移不改判断）——四个方法逐字搬移；两个 Job（列表加载与设备变更）的所有权
 * 随之内聚（原 VM 字段仅这四处使用）。
 *
 * 依赖全经构造器注入：scope / 状态读写 / 文案 / 会话属主校验 / 账号安全仓库。
 */
internal class SettingsDeviceController(
    private val scope: CoroutineScope,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val accountApi: AccountSecurityNetworkRepository = AccountSecurityNetworkRepository(),
) {
    private var devicesLoadJob: Job? = null
    private var deviceMutationJob: Job? = null

    fun loadMyDevices() {
        devicesLoadJob?.cancel()
        devicesLoadJob = scope.launch {
            val userId = com.maodouchat.session.CurrentSession.snapshot().userId
            if (!com.maodouchat.session.CurrentSession.hasSession() || userId.isNullOrBlank()) {
                updateState {
                    it.copy(isLoadingDevices = false, errorMessage = text(R.string.error_session_expired))
                }
                return@launch
            }
            val currentDeviceId = com.maodouchat.security.SignalIdentityAccess.deviceId()
            updateState { it.copy(isLoadingDevices = true, currentDeviceId = currentDeviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = userId,
                )
                ) {
                    if (com.maodouchat.session.CurrentSession.snapshot().userId == userId) {
                        updateState {
                            it.copy(isLoadingDevices = false, errorMessage = text(R.string.error_session_expired))
                        }
                    }
                    return@launch
                }
                accountApi.devices(userId = userId, currentDeviceId = currentDeviceId).fold(
                    onSuccess = { devices ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = userId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
                            it.copy(
                                devices = devices.sortedWith(compareByDescending<DeviceInfoDto> { d -> d.isCurrent }.thenBy { d -> d.deviceId }),
                                isLoadingDevices = false
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(userId)) return@fold
                        updateState { it.copy(isLoadingDevices = false, errorMessage = error.message ?: text(R.string.settings_device_list_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(userId)) {
                    updateState { it.copy(isLoadingDevices = false) }
                }
                throw error
            }
        }
    }

    fun removeMyDevice(deviceId: Int) {
        if (deviceMutationJob?.isActive == true) return
        deviceMutationJob = scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            if (deviceId == currentState().currentDeviceId) {
                updateState { it.copy(errorMessage = text(R.string.settings_cannot_remove_current_device)) }
                return@launch
            }
            updateState { it.copy(removingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    // 8.38：门禁失败复位 removingDeviceId
                    updateState { it.copy(removingDeviceId = null, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.removeDevice(deviceId = deviceId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
                            it.copy(
                                devices = it.devices.filterNot { device -> device.deviceId == deviceId },
                                removingDeviceId = null,
                                successMessage = text(R.string.settings_device_removed)
                            )
                        }
                        loadMyDevices()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(removingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_remove_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(removingDeviceId = null) }
                }
                throw error
            }
        }
    }

    fun renameMyDevice(deviceId: Int, name: String) {
        if (deviceMutationJob?.isActive == true) return
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 50) {
            updateState { it.copy(errorMessage = text(R.string.settings_device_name_length)) }
            return
        }
        deviceMutationJob = scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            updateState { it.copy(renamingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    // 8.38：门禁失败复位 renamingDeviceId
                    updateState { it.copy(renamingDeviceId = null, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.renameDevice(deviceId = deviceId, deviceName = trimmed).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
                            it.copy(
                                devices = it.devices.map { device -> if (device.deviceId == deviceId) device.copy(deviceName = trimmed) else device },
                                renamingDeviceId = null,
                                successMessage = text(R.string.settings_device_name_updated)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(renamingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_name_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(renamingDeviceId = null) }
                }
                throw error
            }
        }
    }

    fun confirmMyDevice(deviceId: Int) {
        if (deviceMutationJob?.isActive == true) return
        deviceMutationJob = scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            val approverDeviceId = com.maodouchat.security.SignalIdentityAccess.deviceId()
            val currentDevice = currentState().devices.firstOrNull { it.deviceId == approverDeviceId }
            val targetDevice = currentState().devices.firstOrNull { it.deviceId == deviceId }
            // 9.140：仅当本地列表已加载且明确显示本机未确认时才拦——列表未加载/过期时
            // 不得本地误拦（服务端 APPROVER_NOT_TRUSTED 是权威校验）
            if (deviceId == approverDeviceId || (currentDevice != null && currentDevice.status != "CONFIRMED")) {
                updateState { it.copy(errorMessage = text(R.string.settings_approve_from_confirmed_device)) }
                return@launch
            }
            val approvalSignature = targetDevice?.identityKey
                ?.let { com.maodouchat.security.SignalIdentityAccess.signDeviceConfirmation(deviceId, it) }
            if (approvalSignature.isNullOrBlank()) {
                updateState { it.copy(errorMessage = text(R.string.settings_device_confirm_proof_failed)) }
                return@launch
            }
            updateState { it.copy(confirmingDeviceId = deviceId, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                accountApi.confirmDevice(deviceId = deviceId, approverDeviceId = approverDeviceId, signature = approvalSignature).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
                            it.copy(
                                devices = it.devices.map { device ->
                                    if (device.deviceId == deviceId) {
                                        device.copy(status = "CONFIRMED", confirmedAt = System.currentTimeMillis(), confirmedByDeviceId = approverDeviceId)
                                    } else {
                                        device
                                    }
                                },
                                confirmingDeviceId = null,
                                successMessage = text(R.string.settings_device_confirmed)
                            )
                        }
                        loadMyDevices()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(confirmingDeviceId = null, errorMessage = error.message ?: text(R.string.settings_device_confirm_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(confirmingDeviceId = null) }
                }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
