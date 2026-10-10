package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureDeveloperAccountRoutes() {
    val userRepo = devUserRepo
    val authTokenRepo = devAuthTokenRepo
    val developerLoginRateLimiter = BoundedRateLimiter()
    /** 8.131：开发者登录按账号限流（防轮换源 IP 爆破，与主登录 loginEmailRateLimiter 同策略）。 */
    val developerLoginEmailRateLimiter = BoundedRateLimiter()
    val developerBotCreateRateLimiter = BoundedRateLimiter()
    val developerBotTokenRateLimiter = BoundedRateLimiter()
    val developerBotSettingsRateLimiter = BoundedRateLimiter()

    // ─── Login (email + password) ────────
    post("/login") {
        if (!developerLoginRateLimiter.acquire(
                call.remoteHost(),
                maxPerMinute = ServerConfig.authRateLimitPerMinute
            )
        ) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
        }
        val body = call.receiveBoundedText().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val (email, password, totpCode) = parseDeveloperLoginFields(obj)
        if (email.isBlank() || password.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮箱或密码不能为空"))
        }
        // 8.131：与主登录一致补按账号限流——此前仅按 IP 限流，攻击者轮换源 IP
        // 即可对同一开发者账号无限爆破（主登录早有 loginEmailRateLimiter 堵这个洞）
        val emailKey = runCatching { email.normalizedEmail() }.getOrDefault(email)
        if (!developerLoginEmailRateLimiter.acquire(emailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号尝试过于频繁，请稍后再试"))
        }
        val loginResult = userRepo.loginWithFactors(email, password, totpCode)
        when {
            !loginResult.passwordOk -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
            }
            loginResult.totpEnabled && !loginResult.totpOk -> {
                // 200 so the console can surface the TOTP step without treating it as a transport error.
                call.respond(
                    DevLoginResponse(
                        requiresTotp = true,
                        token = "",
                        userId = "",
                        email = email,
                        name = "",
                        bots = emptyList()
                    )
                )
            }
            loginResult.user != null -> {
                val user = checkNotNull(loginResult.user)
                // 失败闭合：未配置开发者白名单时拒绝所有人，避免任意已登录账号绕过权限获取开发者会话（权限提升）。
                // 必须通过 DEVELOPER_USER_IDS 显式授权才允许创建/管理机器人。
                if (user.id !in ServerConfig.developerUserIds) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("开发者功能未启用或需要开发者权限"))
                    return@post
                }
                val tokenVersion = authTokenRepo.getAccessTokenVersion(user.id)
                val devToken = mintDevSessionToken(user.id, tokenVersion)
                val bots = BotRepository.listByOwner(user.id)
                call.respond(
                    DevLoginResponse(
                        requiresTotp = false,
                        token = devToken,
                        userId = user.id,
                        email = user.email,
                        name = user.name,
                        bots = bots
                    )
                )
            }
            else -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
            }
        }
    }

    // ─── Current user + bot list ─────────
    get("/me") {
        val userId = devSessionUserId(call)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        val user = userRepo.getById(userId)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
        val bots = BotRepository.listByOwner(userId)
        call.respond(DevMeResponse(userId = user.id, email = user.email, name = user.name, bots = bots))
    }

    // ─── Create bot ──────────────────────
    post("/bots") {
        val userId = devSessionUserId(call)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@post
        if (!RuntimeConfigService.isBotsAllowed()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
        }
        if (!developerBotCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
        }
        val body = call.receiveBoundedText().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val (name, username, description) = parseDeveloperBotCreateFields(obj)
        when (val result = BotRepository.create(userId, name, username, description)) {
            is BotRepository.BotCreateResult.Success -> call.respond(result.bot)
            BotRepository.BotCreateResult.UsernameTaken ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人用户名已被占用"))
            BotRepository.BotCreateResult.MaxBotsReached ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人数量已达上限"))
            BotRepository.BotCreateResult.InvalidInput ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("创建机器人失败（用户名非法）"))
            BotRepository.BotCreateResult.OwnerInvalid ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("开发者账号状态不可用"))
        }
    }

    // ─── Rotate token ────────────────────
    post("/bots/{id}/token") {
        val userId = devSessionUserId(call)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@post
        if (!developerBotTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@post
        val bot = BotRepository.regenerateToken(botId, userId)
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }

    // ─── Set webhook ─────────────────────
    put("/bots/{id}/webhook") {
        val userId = devSessionUserId(call)
            ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@put
        if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
            return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
        val body = call.receiveBoundedText().orEmpty()
        val url = parseDeveloperWebhookUrl(body)
        if (!url.isNullOrBlank() && !BotRepository.isAllowedWebhookUrl(url)) {
            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
        }
        // secret is accepted for forward-compat but not persisted (no repo column yet).
        val bot = BotRepository.setWebhook(botId, userId, url)
            ?: return@put call.respondDeveloperBotUnavailable()
        call.respond(bot)
    }

    // ─── Delete bot ──────────────────────
    delete("/bots/{id}") {
        val userId = devSessionUserId(call)
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@delete
        if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
            return@delete call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@delete
        // 与 REST 删除机器人一致：删除会 bump 群成员版本；此前开发者账号路径只删 DB，
        // 客户端成员列表会残留已删除 bot。
        val affectedGroupIds = BotRepository.groupChatIdsFor(botId)
        val ok = BotRepository.delete(botId, userId)
        if (!ok) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        val groupSnapshots = devParticipantRepo.groupRevisionAndParticipantIds(affectedGroupIds)
        groupSnapshots.forEach { (chatId, snapshot) ->
            notifyGroupRevisionChangedWithData(
                json = devJson,
                chatId = chatId,
                reason = "BOT_REMOVED",
                actorId = userId,
                targetUserId = botId,
                memberRevision = snapshot.first,
                recipientIds = snapshot.second
            )
        }
        call.respond(
        buildJsonObject {
put("ok", true)
        }
    )
    }

    // ─── Enable / disable ────────────────
    put("/bots/{id}/enabled") {
        val userId = devSessionUserId(call)
            ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@put
        if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
            return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
        val body = call.receiveBoundedText().orEmpty()
        val enabled = parseDeveloperBotEnabled(body)
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
        val bot = BotRepository.setEnabled(botId, userId, enabled)
            ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
        call.respond(bot)
    }

    // ─── Set command menu ────────────────
    put("/bots/{id}/commands") {
        val userId = devSessionUserId(call)
            ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        if (call.rejectIfDeveloperMaintenance()) return@put
        if (!developerBotSettingsRateLimiter.acquire(userId, maxPerMinute = 60)) {
            return@put call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
        }
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@put
        if (devSessionOwnedBot(botId, userId) == null) {
            return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
        }
        val body = call.receiveBoundedText().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@put
        // 8.48 以来逐项严格校验，任一条目非法即 400（禁止静默丢弃后误清空菜单）；
        // 仅显式传空数组 = 合法清空。抽取逻辑见 parseDeveloperBotCommands。
        val defs = when (val parsed = parseDeveloperBotCommands(obj)) {
            is DeveloperBotCommandsParseResult.Ok -> parsed.defs.map { (command, description) ->
                BotRepository.BotCommandDef(command = command, description = description)
            }
            is DeveloperBotCommandsParseResult.Invalid -> return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(parsed.message)
            )
        }
        val normalized = BotRepository.normalizeCommands(defs)
            ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid commands (max 100, unique a-z0-9_, description required)")
            )
        val saved = BotRepository.setMyCommands(botId, normalized)
            ?: return@put call.respondDeveloperBotUnavailable()
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("commands", saved)
put("count", saved.size)
        }
    )
    }

    // ─── Get command menu ────────────────
    get("/bots/{id}/commands") {
        val userId = devSessionUserId(call)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
        val botId = call.requirePathParamOr400("id", "missing botId") ?: return@get
        if (devSessionOwnedBot(botId, userId) == null) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作该机器人"))
        }
        val commands = BotRepository.getMyCommands(botId)
        call.respond(
        buildJsonObject {
putJsonElement("commands", commands)
put("count", commands.size)
        }
    )
    }
}
