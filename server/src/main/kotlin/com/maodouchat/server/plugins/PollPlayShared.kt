package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.PollRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

// 群玩法各簇共用的小工具与 Application 注册门面（private→internal，零行为改动）。

/**
 * 群玩法 B3 路由：群签到+排行 / 群接龙 / 群 PK / 投票同步。
 *
 * 与 Routing.kt 的投票 CRUD 端点互补（不重复注册已有端点），
 * 写操作成功后通过 [sendToUser] 向群成员推送 GROUP_PLAY_UPDATE 事件，
 * 客户端收到后自行拉取最新快照。
 *
 * 接入点：Application.kt 中 configureRouting(...) 之后调用 configurePollRouting()。
 */
internal val pollLogger = LoggerFactory.getLogger("PollRouting")
// internal 供测试引用同一份实例，钉住 pollJson 与默认 Json 在 parseToJsonElement 上的等价。
internal val pollJson = Json { ignoreUnknownKeys = true }
internal const val MAX_BODY_CHARS = 32_768
/** 群玩法写入频率限制：防止刷量放大。 */
internal val pollRateLimiter = BoundedRateLimiter()
internal val userRepoForSuspension = UserRepository()
/** 8.33：封禁用户不得参与群玩法写入（签到/接龙/PK/投票）——与其余写路径 rejectIfSuspended 一致。 */
internal suspend fun ApplicationCall.rejectIfSuspendedForPolls(userId: String): Boolean {
    val until = userRepoForSuspension.getSuspendedUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse("账号已被临时封禁"))
    return true
}
internal suspend fun ApplicationCall.rejectIfMutedForPolls(chatId: String, userId: String): Boolean {
    if (!PollRepository.isMuted(chatId, userId)) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
    return true
}
/** 向群成员广播群玩法更新事件（WS）。推送失败不影响主流程。 */
internal suspend inline fun <reified T> broadcastGroupPlayUpdate(
    chatId: String,
    event: String,
    crossinline payloadForViewer: (String) -> T?
) {
    val members = PollRepository.memberIds(chatId)
    if (members.isEmpty()) return
    // 9.152：不得复用操作者视角 payload——CheckinDto.alreadyCheckedIn / ChainDto.myJoined /
    // PkDto.myChoice 均为按收件人计算（PK 投票本为隐私选择，myChoice 仅本人可见），
    // 此前「拉黑集合相同即复用操作者 payload」的捷径会把操作者的个人字段发给其他成员
    //（隐私泄露 + 客户端误显示为本人状态）。逐收件人生成 payload 并与发送同协程并行。
    coroutineScope {
        members.map { uid ->
            async {
                val payload = payloadForViewer(uid) ?: return@async
                runCatching { LocalRealtimeBus.publish(uid, groupPlayWsMessage(event, payload)) }
                    .onFailure { pollLogger.debug("GROUP_PLAY_UPDATE push failed user={}: {}", uid, it.message) }
            }
        }.forEach { it.await() }
    }
}
internal inline fun <reified T> groupPlayWsMessage(event: String, payload: T): String {
    // 与其它 WsMessage 一致：payload 为序列化后的 JSON 字符串，内容为 {event, data}
    val wrapped = buildJsonObject {
        put("event", event)
        put("data", Json.parseToJsonElement(pollJson.encodeToString(payload)))
    }
    return pollJson.encodeToString(WsMessage("GROUP_PLAY_UPDATE", wrapped.toString()))
}

fun Application.configurePollRouting() {
    routing {
        configurePollCheckinRoutes()
        configurePollChainRoutes()
        configurePollPkRoutes()
        configurePollSyncRoutes()
    }
}
