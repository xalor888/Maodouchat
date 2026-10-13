package com.maodouchat.server.repository

// 默认用户种子数据簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.Users
import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.service.normalizedEmail
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun UserRepository.createDefaultUsers() {
    val defaults = listOf(
        Triple("u1", "陈亚历克斯", "alex@example.com"),
        Triple("u2", "Alice Chen", "alice@example.com"),
        Triple("u3", "Bob Smith", "bob@example.com"),
        Triple("u4", "Elena Rodriguez", "elena@example.com"),
        Triple("u5", "Sarah Jenkins", "sarah@example.com"),
        Triple("u6", "Arthur Morgan", "arthur@example.com"),
        Triple("u7", "张三", "zhangsan@example.com"),
        Triple("u8", "李四", "lisi@example.com"),
        Triple("u9", "王五", "wangwu@example.com"),
        Triple("u10", "David Kim", "david@example.com"),
        Triple("u11", "Emma Watson", "emma@example.com"),
        Triple("u12", "赵六", "zhaoliu@example.com"),
        Triple("u13", "孙七", "sunqi@example.com"),
    )
    defaults.forEach { (id, name, email) ->
        try {
            transaction {
                if (Users.selectAll().where { Users.id eq id }.empty()) {
                    Users.insert {
                        it[Users.id] = id
                        it[Users.name] = name
                        it[Users.email] = email
                        it[passwordHash] = BCrypt.withDefaults().hashToString(12, "password123".toCharArray())
                        it[isOnline] = false
                        it[status] = "在线"
                        it[showOnline] = true
                        it[searchable] = true
                        it[defaultPostVisibility] = "PUBLIC"
                        it[isModerator] = email.normalizedEmail() in ServerConfig.moderatorEmails
                    }
                }
            }
        } catch (error: Exception) {
            if (!isUniqueViolation(error)) throw error
        }
    }
}
