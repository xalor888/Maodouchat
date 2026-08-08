package com.maodouchat.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "identity_trust",
    primaryKeys = ["accountId", "remoteUserId", "deviceId"]
)
data class IdentityTrustEntity(
    val accountId: String,
    val remoteUserId: String,
    val deviceId: Int,
    val identityKeyBase64: String,
    val trustState: String,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val verifiedAt: Long? = null
)
