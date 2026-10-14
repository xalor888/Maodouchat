package com.maodouchat.security.device

sealed interface DeviceRevocationResult {
    data class RemoteDeviceRevoked(val deviceId: Int) : DeviceRevocationResult
    object CurrentDeviceRevoked : DeviceRevocationResult
}
