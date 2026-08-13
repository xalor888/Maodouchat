package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PollRepository
import com.maodouchat.server.db.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 群玩法 B3 路由：群签到+排行 / 群接龙 / 群 PK / 投票同步。
 *
 * 与 Routing.kt 的投票 CRUD 端点互补（不重复注册已有端点），
 * 写操作成功后通过 [sendToUser] 向群成员推送 GROUP_PLAY_UPDATE 事件，
 * 客户端收到后自行拉取最新快照。
 *
 * 接入点：Application.kt 中 configureRouting(...) 之后调用 configurePollRouting()。
 */
private val pollLogger = LoggerFactory.getLogger("PollRouting")

private val pollJson = Json { ignoreUnknownKeys = true }

private const val MAX_BODY_CHARS = 32_768
private const val MAX_CHAIN_TITLE_LENGTH = 200
private const val MAX_CHAIN_TOPIC_LENGTH = 500
private const val MAX_CHAIN_CONTENT_LENGTH = 500
private const val MAX_PK_TITLE_LENGTH = 120

/** 群玩法写入频率限制：防止刷量放大。 */
private val pollRateLimiter = BoundedRateLimiter()

/** 8.33：封禁用户不得参与群玩法写入（签到/接龙/PK/投票）——与其余写路径 rejectIfSuspended 一致。 */
private suspend fun ApplicationCall.rejectIfSuspendedForPolls(userId: String): Boolean {
    val until = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.suspendedUntil) ?: 0L
    }
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse("账号已被临时封禁"))
    return true
}

private suspend fun ApplicationCall.rejectIfMutedForPolls(chatId: String, userId: String): Boolean {
    if (!PollRepository.isMuted(chatId, userId)) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
    return true
}

fun Application.configurePollRouting() {
    routing {
        configurePollRoutes()
    }
}

fun Routing.configurePollRoutes() {
    authenticate("auth-jwt") {

        // ── 群签到 ─────────────────────────────────────
        post("/api/chats/{chatId}/checkins") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.parameters["chatId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            // 8.47：成员校验先于限流——非成员不应能消耗自己对该 chat 的配额
            //（此前限流先行，非成员可反复探测耗尽配额）
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:checkin", maxPerMinute = 20)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("签到过于频繁，请稍后再试"))
            }
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val dto = GroupCheckinRepository.checkIn(chatId, userId)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法签到"))
            broadcastGroupPlayUpdate(chatId, "checkin_updated", dto)
            call.respond(dto)
        }

        get("/api/chats/{chatId}/checkins/me") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            // 8.39：与同文件其余读端点一致，先校验成员返回 403（此前落到 404「签到信息不存在」，
            // 把非成员与群不存在合并暴露权限边界）
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val dto = GroupCheckinRepository.myCheckin(chatId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("签到信息不存在"))
            call.respond(dto)
        }

        get("/api/chats/{chatId}/checkins/rank") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            call.respond(GroupCheckinRepository.checkinRanking(chatId, limit, viewerId = userId))
        }

        // ── 群接龙 ─────────────────────────────────────
        post("/api/chats/{chatId}/chains") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.parameters["chatId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            if (!pollRateLimiter.acquire("$userId:$chatId:chain_create", maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建接龙过于频繁"))
            }
            val obj = call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)
                .let { runCatching { pollJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
            val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
            val topic = obj["topic"]?.jsonPrimitive?.content.orEmpty()
            val maxEntries = obj["maxEntries"]?.jsonPrimitive?.intOrNull ?: 200
            if (title.isBlank() || title.length > MAX_CHAIN_TITLE_LENGTH) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙标题无效"))
            }
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            val chain = GroupCheckinRepository.createChain(chatId, userId, title, topic, maxEntries)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建接龙"))
            broadcastGroupPlayUpdate(chatId, "chain_created", chain)
            call.respond(chain)
        }

        get("/api/chats/{chatId}/chains") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            call.respond(GroupCheckinRepository.listChains(chatId, userId, limit))
        }

        get("/api/chains/{chainId}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chainId = call.parameters["chainId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chainId"))
            val chain = GroupCheckinRepository.getChain(chainId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("接龙不存在"))
            call.respond(chain)
        }

        post("/api/chains/{chainId}/entries") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chainId = call.parameters["chainId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chainId"))
            // 8.52 一致性：接龙不存在或非成员统一 403（与 PK 投票口径一致），
            // 并在限流前拦截，避免非成员/禁言成员消耗群玩法配额。
            val chainForMute = GroupCheckinRepository.getChain(chainId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            if (call.rejectIfMutedForPolls(chainForMute.chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chainId:chain_join", maxPerMinute = 30)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("接龙过于频繁"))
            }
            val obj = call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)
                .let { runCatching { pollJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            if (content.isBlank() || content.length > MAX_CHAIN_CONTENT_LENGTH) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙内容无效"))
            }
            val chain = GroupCheckinRepository.joinChain(chainId, userId, content)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("接龙已结束或人数已满"))
            broadcastGroupPlayUpdate(chain.chatId, "chain_updated", chain)
            call.respond(chain)
        }

        // ── 群 PK ─────────────────────────────────────
        post("/api/chats/{chatId}/pk") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val chatId = call.parameters["chatId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            // 8.47：成员校验先于限流（同 checkin 口径）
            if (!PollRepository.isMember(chatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(chatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$chatId:pk_create", maxPerMinute = 10)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建 PK 过于频繁"))
            }
            val obj = call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)
                .let { runCatching { pollJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
            val leftTitle = obj["leftTitle"]?.jsonPrimitive?.content.orEmpty()
            val rightTitle = obj["rightTitle"]?.jsonPrimitive?.content.orEmpty()
            if (leftTitle.isBlank() || rightTitle.isBlank() ||
                leftTitle.length > MAX_PK_TITLE_LENGTH || rightTitle.length > MAX_PK_TITLE_LENGTH
            ) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("PK 双方标题无效"))
            }
                        // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
val pk = GroupCheckinRepository.createPk(chatId, userId, leftTitle, rightTitle)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建 PK"))
            broadcastGroupPlayUpdate(chatId, "pk_created", pk)
            call.respond(pk)
        }

        get("/api/chats/{chatId}/pk") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            if (!PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            call.respond(GroupCheckinRepository.listChatPks(chatId, userId, limit))
        }

        get("/api/pk/{pkId}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val pkId = call.parameters["pkId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pkId"))
            val pk = GroupCheckinRepository.getPk(pkId, userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("PK 不存在"))
            call.respond(pk)
        }

        post("/api/pk/{pkId}/vote") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val pkId = call.parameters["pkId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pkId"))
            // 8.47：成员校验先于限流（同 checkin 口径）；非成员不得消耗投票配额
            val pkChatId = GroupCheckinRepository.getPk(pkId, userId)?.chatId
            if (pkChatId == null || !PollRepository.isMember(pkChatId, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            if (call.rejectIfMutedForPolls(pkChatId, userId)) return@post
            if (!pollRateLimiter.acquire("$userId:$pkId:pk_vote", maxPerMinute = 30)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("投票过于频繁"))
            }
            val obj = call.receiveBoundedTextOrEmpty(MAX_BODY_CHARS)
                .let { runCatching { pollJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
            val choice = obj["choice"]?.jsonPrimitive?.content.orEmpty()
            // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
            val pk = GroupCheckinRepository.votePk(pkId, userId, choice)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("PK 投票失败：仅限群成员且未结束"))
            broadcastGroupPlayUpdate(pk.chatId, "pk_updated", pk)
            call.respond(pk)
        }

        post("/api/pk/{pkId}/close") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspendedForPolls(userId)) return@post
            val pkId = call.parameters["pkId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pkId"))
            val pk = GroupCheckinRepository.closePk(pkId, userId)
                ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("只有创建者可关闭 PK"))
            broadcastGroupPlayUpdate(pk.chatId, "pk_closed", pk)
            call.respond(pk)
        }

        // ── 投票同步（补充端点，不重复 Routing.kt 已有 CRUD）──
        get("/api/chats/{chatId}/polls/sync") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
            if (!PollRepository.isGroupChat(chatId) || !PollRepository.isMember(chatId, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            call.respond(PollRepository.listChatPollSnapshots(chatId, limit, viewerId = userId))
        }
    }
}

/** 向群成员广播群玩法更新事件（WS）。推送失败不影响主流程。 */
private suspend inline fun <reified T> broadcastGroupPlayUpdate(chatId: String, event: String, payload: T) {
    // 与其它 WsMessage 一致：payload 为序列化后的 JSON 字符串，内容为 {event, data}
    val wrapped = buildJsonObject {
        put("event", event)
        put("data", Json.parseToJsonElement(pollJson.encodeToString(payload)))
    }
    val message = pollJson.encodeToString(WsMessage("GROUP_PLAY_UPDATE", wrapped.toString()))
    val members = PollRepository.memberIds(chatId)
    // 8.47：并发 fan-out——此前逐成员串行 await，任一慢客户端（≤500 成员）拖慢该群
    // 全部写端点的响应；sendToUser 按用户 session 加锁，跨用户并发安全。
    if (members.size <= 1) {
        members.forEach { uid ->
            runCatching { sendToUser(uid, message) }
                .onFailure { pollLogger.debug("GROUP_PLAY_UPDATE push failed user={}: {}", uid, it.message) }
        }
    } else {
        coroutineScope {
            members.map { uid ->
                async {
                    runCatching { sendToUser(uid, message) }
                        .onFailure { pollLogger.debug("GROUP_PLAY_UPDATE push failed user={}: {}", uid, it.message) }
                }
            }.forEach { it.await() }
        }
    }
}
