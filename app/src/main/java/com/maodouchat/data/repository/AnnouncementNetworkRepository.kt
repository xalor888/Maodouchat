package com.maodouchat.data.repository

import com.maodouchat.network.ApiService

/**
 * 运营公告的**远端**读取与回执（G328c）。
 *
 * 两个端点都返回**服务端原始 JSON 文本**（历史接口如此），解析留给调用方——
 * 与 `BotNetworkRepository.listBots` 同样的取舍：把解析搬进仓库会改变错误处理的位置
 * （从「调用方一条提示」变成「仓库层异常」），不属于搬移该做的事。
 */
internal class AnnouncementNetworkRepository(
    private val activeApi: suspend (String) -> Result<String> =
        { token -> ApiService.getActiveAnnouncements(token) },
    private val ackApi: suspend (String, String) -> Result<String> =
        { token, id -> ApiService.ackAnnouncement(token, id) },
) {
    /** 当前生效的公告（原始 JSON）。 */
    suspend fun active(token: String): Result<String> = activeApi(token)

    /** 回执某条公告（原始 JSON）。 */
    suspend fun ack(token: String, announcementId: String): Result<String> = ackApi(token, announcementId)
}
