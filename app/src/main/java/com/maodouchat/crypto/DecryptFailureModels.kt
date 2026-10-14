package com.maodouchat.crypto

enum class Disposition {
    /** 调用方应视为终态：ACK、写入占位、不再对同一密文调 SessionCipher。 */
    STOP,
    /** 会话/身份/epoch 仍可能恢复，允许再试。 */
    RETRY,
}

enum class TrackingAction {
    CLEAR,
    RECORD_FAILURE,
    MARK_TERMINAL,
}
