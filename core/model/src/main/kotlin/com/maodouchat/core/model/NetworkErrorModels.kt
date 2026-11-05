package com.maodouchat.core.model

enum class ApiFailureKind {
    HTTP,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}

enum class WebSocketErrorKind {
    CONNECTION,
    ENVELOPE_PARSE,
    MESSAGE_PARSE,
    POST_DELETE_PARSE,
    PIN_PARSE,
    DISAPPEARING_PARSE,
    USER_STATUS_PARSE,
    TYPING_PARSE,
    GROUP_REVISION_PARSE,
    SIGNALING_PARSE,
    FRIEND_REQUEST_PARSE
}
