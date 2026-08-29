package com.maodouchat.core.model

/** 冻结 ID 生成接口：领域层不直接 UUID.randomUUID()，便于测试注入确定性 ID。 */
fun interface IdGenerator {
    fun nextId(prefix: String): String
}

object UuidIdGenerator : IdGenerator {
    override fun nextId(prefix: String): String =
        "${prefix}_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
}
