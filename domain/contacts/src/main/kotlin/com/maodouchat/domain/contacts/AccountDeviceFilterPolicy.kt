package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto

// 账号安全设备搜索：设备名、设备 id、状态任一含查询词即命中。
// 自 SettingsAccountSecurityScreen 的 filteredDevices 收敛。
object AccountDeviceFilterPolicy {

    fun matches(device: DeviceInfoDto, query: String): Boolean =
        device.deviceName.contains(query, ignoreCase = true) ||
            device.deviceId.toString().contains(query, ignoreCase = true) ||
            device.status.contains(query, ignoreCase = true)

    fun filter(devices: List<DeviceInfoDto>, query: String): List<DeviceInfoDto> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return devices
        return devices.filter { matches(it, trimmed) }
    }
}
