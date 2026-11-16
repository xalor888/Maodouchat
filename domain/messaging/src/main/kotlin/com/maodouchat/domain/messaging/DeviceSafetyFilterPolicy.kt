package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DeviceSafetyState

// 安全码设备搜索：设备 id、信任状态名、安全码、身份密钥任一含查询词即命中。
// 自 ChatDetailSafetyCode 的 filteredDevices 收敛。
object DeviceSafetyFilterPolicy {

    fun matches(device: DeviceSafetyState, query: String): Boolean =
        device.deviceId.toString().contains(query, ignoreCase = true) ||
            device.trustState.name.contains(query, ignoreCase = true) ||
            device.safetyCode.orEmpty().contains(query, ignoreCase = true) ||
            device.identityKey.orEmpty().contains(query, ignoreCase = true)

    fun filter(devices: List<DeviceSafetyState>, query: String): List<DeviceSafetyState> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return devices
        return devices.filter { matches(it, trimmed) }
    }
}
