package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maodouchat.data.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val avatar: String? = null,
    val email: String = "",
    val isOnline: Boolean = false,
    val status: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val nickname: String? = null,
    /** 最后上线时间（8.38：此前不落库，重启后联系人 lastSeen 全丢）。 */
    val lastSeen: Long = 0
)

fun UserEntity.toDomain(): User = User(
    id = id,
    name = name,
    avatar = avatar,
    email = email,
    isOnline = isOnline,
    status = status,
    nickname = nickname,
    lastSeen = lastSeen
)

fun User.toEntity(): UserEntity = UserEntity(
    id = id,
    name = name,
    avatar = avatar,
    email = email,
    isOnline = isOnline,
    status = status,
    nickname = nickname,
    lastSeen = lastSeen
)
