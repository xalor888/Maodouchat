package com.maodouchat.server.repository


/** 会话总览里的 signal 设备条目。 */
data class AdminSignalDeviceRow(
    val deviceId: Int,
    val deviceName: String,
    val status: String,
    val lastSeenAt: Long,
    val createdAt: Long,
)

/** 会话总览里的 push token 条目。 */
data class AdminPushTokenRow(
    val deviceId: String,
    val platform: String,
    val updatedAt: Long,
)

/**
 * 管理搜索返回的消息**元数据**。
 *
 * 刻意没有「内容」字段：人类消息正文对服务端不透明，这里永远读不到、也不该读。
 */
data class AdminMessageMetadataRow(
    val id: String,
    val chatId: String,
    val senderId: String,
    val kind: String,
    val timestamp: Long,
)

data class AdminMessageSearchFilter(
    val q: String,
    val chatId: String,
    val userId: String,
    val limit: Int,
    val offset: Int,
)

/**
 * M2/G15：管理后台各路由里直写事务的归属地。
 *
 * 职责：**本类只负责「取数据 / 写审计」**，响应 JSON 的形状仍由路由决定——路由管 HTTP，
 * repository 管 SQL。这一条是 `ServerArchitectureTest` 的棘轮在守的契约。
 *
 * 各方法的 SQL 都是从对应路由的 handler 内**逐字**搬过来的（搬迁前后行为一致），
 * 唯一的改动是把 `Chats` / `RiskEvents` 等表的列名对齐到 `db/` 下各表文件的真实定义。
 */
class AdminManagementRepository
