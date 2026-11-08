package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DeviceCiphertext
import com.maodouchat.core.model.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.core.model.MessagingV2DeviceTarget

/** 加密目标用户 id：按快照目标顺序去重，加密调用按此顺序排队。 */
fun targetUserIds(targets: Set<MessagingV2DeviceTarget>): LinkedHashSet<String> =
    targets.mapTo(linkedSetOf()) { it.userId }

/** 必达收件人：属主本人不参与多收件人加密，从目标用户里排除后才进加密调用。 */
fun requiredRecipientIds(targetUserIds: Set<String>, ownerUserId: String): Set<String> =
    targetUserIds.filterNot { it == ownerUserId }.toSet()

/** 设备密文 → 线上信封：ciphertextType 走线上归一化（见 OutboxWireFormat）。 */
fun deviceEnvelopes(ciphertexts: List<DeviceCiphertext>): List<EncryptedDeviceEnvelopeRequestV2> =
    ciphertexts.map {
        EncryptedDeviceEnvelopeRequestV2(
            recipientUserId = it.userId,
            recipientDeviceId = it.deviceId,
            ciphertextType = OutboxWireFormat.ciphertextType(it.ciphertextType),
            ciphertext = it.ciphertext,
        )
    }

/** Sender Key 群信封：目标按用户 id 再设备 id 排序，密文类型恒为 SENDER_KEY。 */
fun senderKeyEnvelopes(
    targets: Set<MessagingV2DeviceTarget>,
    ciphertext: String,
): List<EncryptedDeviceEnvelopeRequestV2> =
    targets.sortedWith(compareBy({ it.userId }, { it.deviceId })).map {
        EncryptedDeviceEnvelopeRequestV2(
            recipientUserId = it.userId,
            recipientDeviceId = it.deviceId,
            ciphertextType = "SENDER_KEY",
            ciphertext = ciphertext,
        )
    }

/** 信封覆盖判定：已加密设备集合与快照目标集合相等才算全覆盖，顺序无关。 */
fun envelopeTargetsCovered(
    envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
    targets: Set<MessagingV2DeviceTarget>,
): Boolean = envelopes.mapTo(linkedSetOf()) {
    MessagingV2DeviceTarget(it.recipientUserId, it.recipientDeviceId)
} == targets
