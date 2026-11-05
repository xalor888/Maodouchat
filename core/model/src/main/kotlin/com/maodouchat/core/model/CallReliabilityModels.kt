package com.maodouchat.core.model

enum class CallMediaPermission { MICROPHONE, CAMERA }

enum class IceReconnectAction {
    START_GRACE,
    CANCEL_GRACE,
    RESTART_ICE,
    END_NOW,
    IGNORE
}
