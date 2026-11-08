package com.maodouchat.domain.messaging

import com.maodouchat.core.model.EncryptedDeviceEnvelopeRequestV2

/** 出站消息就绪后的待发形态：群世代 + 附件 id + 每设备密文信封。纯模型。 */
data class PreparedMessageV2(
    val groupRevision: Long?,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
)
