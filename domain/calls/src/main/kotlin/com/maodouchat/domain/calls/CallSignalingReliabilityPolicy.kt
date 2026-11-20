package com.maodouchat.domain.calls

/** User actions must always name the exact active call; unlike initial signaling they cannot bind a session. */
fun CallReliabilityPolicy.shouldAcceptHangUpAction(activeCallId: String, requestedCallId: String): Boolean =
    activeCallId == requestedCallId

private val CRITICAL_SIGNALING_TYPES = setOf("offer", "answer", "reject", "busy", "hang-up")

fun CallReliabilityPolicy.isCriticalSignalingType(type: String): Boolean =
    normalizeSignalingType(type) in CRITICAL_SIGNALING_TYPES
