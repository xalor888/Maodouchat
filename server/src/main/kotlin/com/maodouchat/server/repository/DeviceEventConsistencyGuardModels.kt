package com.maodouchat.server.repository

enum class Status { APPLIED, STALE, DUPLICATE, OUT_OF_ORDER }
data class ApplyOutcome(val status: Status, val lastAppliedSeq: Long)
