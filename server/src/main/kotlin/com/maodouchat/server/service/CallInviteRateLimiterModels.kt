package com.maodouchat.server.service

internal data class Attempt(val key: String, val timestamp: Long)

internal data class DedupEntry(val hits: Int, val lastAccess: Long)
