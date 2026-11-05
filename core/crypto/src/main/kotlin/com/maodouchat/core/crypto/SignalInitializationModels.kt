package com.maodouchat.core.crypto

enum class SignalInitializationAction(val rebuildsLocalStore: Boolean) {
    REUSE(rebuildsLocalStore = false),
    UPLOAD_ONLY(rebuildsLocalStore = false),
    FULL_INITIALIZATION(rebuildsLocalStore = true),
}
