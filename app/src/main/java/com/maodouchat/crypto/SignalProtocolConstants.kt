package com.maodouchat.crypto

import kotlinx.serialization.json.Json
import java.security.SecureRandom

internal object SignalProtocolConstants {
    const val TAG = "SignalProtocol"
    const val ENVELOPE_VERSION = 2
    const val MULTI_DEVICE_ENVELOPE_VERSION = 3
    const val SENDER_KEY_ENVELOPE_VERSION = 1
    const val SENDER_KEY_DISTRIBUTION_VERSION = 1
    const val ALGORITHM_SIGNAL = "signal-v2"
    const val ALGORITHM_SIGNAL_MULTI_DEVICE = "signal-multi-device-v1"
    const val ALGORITHM_SENDER_KEY = "signal-sender-key-v1"
    const val ALGORITHM_SENDER_KEY_DISTRIBUTION = "signal-sender-key-distribution-v1"
    const val PAYLOAD_TEXT = "TEXT"
    const val KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX = "group_distribution_epoch:"
    const val KEY_GROUP_DISTRIBUTION_METADATA_PREFIX = "group_distribution_meta:"
    const val MAX_COUNTED_GROUP_MESSAGE_IDS = 2_000
    const val GROUP_SENDER_KEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val GROUP_SENDER_KEY_MAX_MESSAGES = 1000
    const val DEFAULT_DEVICE_ID = 1
    const val MIN_DEVICE_ID = 1
    const val MIN_GENERATED_DEVICE_ID = 2
    const val MAX_DEVICE_ID = 255
    const val MAX_DEVICE_ID_ALLOCATION_ATTEMPTS = 4
    const val PRE_KEY_COUNT = 50
    /** PreKey 数量低于此阈值时触发运行时补充 */
    const val PRE_KEY_REPLENISH_THRESHOLD = 10
    /** Signed PreKey 轮换周期（天） */
    const val SIGNED_PRE_KEY_ROTATION_DAYS = 7
    /** Signed PreKey 轮换周期（毫秒） */
    val SIGNED_PRE_KEY_ROTATION_MS = SIGNED_PRE_KEY_ROTATION_DAYS * 24L * 60L * 60L * 1_000L
    const val CIPHERTEXT_TYPE_PREKEY = "prekey"
    const val CIPHERTEXT_TYPE_SIGNAL = "signal"
    const val CIPHERTEXT_TYPE_UNKNOWN = "unknown"
    const val KEY_REGISTRATION_ID = "registration_id"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_ID_MIGRATION_PENDING = "device_id_migration_pending"
    const val KEY_IDENTITY_KEY_PAIR = "identity_key_pair"
    const val KEY_SIGNED_PRE_KEY = "signed_pre_key"
    const val KEY_PRE_KEYS = "pre_keys"
    const val KEY_GROUP_DISTRIBUTION_PREFIX = "group_distribution:"
    const val ANONYMOUS_ACCOUNT_ID = "anonymous"
    const val TRUST_VERIFIED = "VERIFIED"
    const val TRUST_CHANGED = "CHANGED"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val secureRandom = SecureRandom()
}
