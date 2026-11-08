package com.maodouchat.domain.messaging

import java.util.UUID

/** 回执消息 id：由动作、目标消息、属主与设备 id 确定性派生，同一输入恒得同一 id。 */
fun receiptMessageId(action: String, targetMessageId: String, owner: String, deviceId: Int): String {
    val seed = "$action|$targetMessageId|$owner|$deviceId"
    return "r_${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"
}
