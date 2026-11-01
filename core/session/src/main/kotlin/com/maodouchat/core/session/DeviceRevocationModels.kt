package com.maodouchat.core.session

sealed interface DeviceRevocationResult {
    data class RemoteDeviceRevoked(val deviceId: Int) : DeviceRevocationResult
    object CurrentDeviceRevoked : DeviceRevocationResult
}
