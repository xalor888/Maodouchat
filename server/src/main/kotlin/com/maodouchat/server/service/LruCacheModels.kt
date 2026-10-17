package com.maodouchat.server.service

internal data class CacheEntry<V>(
    val value: V,
    val createdAt: Long
)
