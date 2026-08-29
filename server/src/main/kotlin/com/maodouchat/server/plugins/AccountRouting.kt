package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.CacheService
import com.maodouchat.server.service.EncryptedAttachmentStorage
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun Route.configureAccountRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    notificationPreferenceRepo: NotificationPreferenceRepository,
    nearbyRepo: NearbyRepository,
    cacheService: CacheService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    userSearchRateLimiter: BoundedRateLimiter,
    nearbyUpdateRateLimiter: BoundedRateLimiter,
    nearbyQueryRateLimiter: BoundedRateLimiter,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
            get("/api/users/me") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val user = userRepo.getById(userId)
                if (user != null) call.respond(user)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // P0 修复后续：推送 HMAC 签名密钥经认证通道下发（此前匿名暴露在 /api/public/status，
            // 任何客户端可拿密钥伪造 FCM 签名）。密钥按用户派生（HMAC(master, userId)），
            // 仅认证用户可取**自己的**派生密钥——只能伪造发给自己的推送，无法伪造他人。
            get("/api/push/verify-key") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val secret = com.maodouchat.server.config.ServerConfig.pushHmacSecret
                if (secret.isBlank() || secret.startsWith("dev-only-")) {
                    call.respond(buildJsonObject { put("key", JsonNull) })
                } else {
                    call.respond(buildJsonObject { put("key", com.maodouchat.server.service.FcmPushService.pushKeyForUser(userId)) })
                }
            }

            get("/api/users") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@get
                }
                // 8.38：与 /api/users/search 一致截断 q 到 100（底层 LIKE 四列全表扫描）
                val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                if (q.isBlank()) {
                    call.respond(userRepo.getAll(limit, offset = offset, viewerId = userId))
                } else {
                    if (q.length < 2) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                        return@get
                    }
                    call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
                }
            }

            get("/api/users/search") {
                // 8.33 修复：q 截断到 100 字符（底层 LIKE 全表扫描，超长关键字无意义且放大成本）
                val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                if (q.length < 2) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@get
                }
                call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
            }

            get("/api/users/privacy") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val privacy = userRepo.getPrivacy(userId)
                if (privacy != null) call.respond(privacy)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            put("/api/users/privacy") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.38：封禁用户不得改隐私（与头像/资料/附近位置一致，防关闭 searchable 逃避检索处置）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val req = call.receiveBoundedText()?.let { parseJson<UpdatePrivacyRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
                if (req.defaultPostVisibility != null && !isValidPostVisibility(req.defaultPostVisibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态可见范围无效"))
                    return@put
                }
                val update = userRepo.updatePrivacyWithTransitions(
                    userId,
                    showOnline = req.showOnline,
                    showStatus = req.showStatus,
                    searchable = req.searchable,
                    defaultPostVisibility = req.defaultPostVisibility,
                    onlineVisibility = req.onlineVisibility
                )
                if (update == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@put
                }
                if (update.onlineRevoked || update.statusRevoked) {
                    broadcastUserVisibilityRevoked(
                        userId = userId,
                        onlineRevoked = update.onlineRevoked,
                        statusRevoked = update.statusRevoked,
                        json = json,
                        userRepo = userRepo
                    )
                }
                call.respond(update.privacy)
            }

            get("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(nearbyRepo.getStatus(userId))
            }

            put("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@put
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得更新附近位置（位置 = 实时行踪，封禁期间必须消失）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                // 每用户限流：位置更新是 DB 写，防高频轮询刷写
                if (!nearbyUpdateRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("位置更新太频繁，请稍后再试"))
                    return@put
                }
                val req = call.receiveBoundedText()?.let { parseJson<UpdateNearbyLocationRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("位置参数无效"))
                    return@put
                }
                val status = nearbyRepo.updateLocation(userId, req.latitude, req.longitude)
                if (status == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("位置参数无效"))
                else call.respond(status)
            }

            delete("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@delete
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                nearbyRepo.stopSharing(userId)
                call.respond(NearbyLocationStatusResponse(false, 0))
            }

            get("/api/users/nearby") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得查询附近的人（位置隐私双向一致）
                if (call.rejectIfSuspended(userRepo, userId)) return@get
                // 每用户限流：附近查询是范围扫描 + haversine 计算，防高频轮询打 CPU
                if (!nearbyQueryRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("查询太频繁，请稍后再试"))
                    return@get
                }
                val radiusKm = (call.request.queryParameters["radiusKm"]?.toDoubleOrNull() ?: 10.0).coerceIn(0.5, 30.0)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                call.respond(nearbyRepo.getNearby(userId, radiusKm, limit))
            }

            get("/api/users/notification-settings") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(notificationPreferenceRepo.getSettings(userId))
            }

            put("/api/users/notification-settings") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<NotificationSettingsRequest>(it) }
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                call.respond(notificationPreferenceRepo.updateSettings(userId, req))
            }

            post("/api/users/push-tokens") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.subject
                val authSessionId = JwtConfig.authSessionId(principal.payload)!!
                val req = call.receiveBoundedText()?.let { parseJson<RegisterPushTokenRequest>(it) }
                val deviceId = req?.deviceId?.trim().orEmpty()
                val token = req?.token?.trim().orEmpty()
                val platform = req?.platform?.trim()?.uppercase().orEmpty()
                if (req == null || !deviceId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$")) ||
                    token.length !in 32..512 || token.any(Char::isWhitespace) ||
                    platform != "ANDROID" || req.timezoneOffsetMinutes !in -1080..1080
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("推送令牌无效"))
                    return@post
                }
                if (!pushTokenRepo.register(
                        userId,
                        deviceId,
                        token,
                        platform,
                        req.timezoneOffsetMinutes,
                        authSessionId
                    )
                ) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录会话已被撤销"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            delete("/api/users/push-tokens") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<RemovePushTokenRequest>(it) }
                val deviceId = req?.deviceId?.trim().orEmpty()
                if (!deviceId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$"))) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备标识无效"))
                    return@delete
                }
                pushTokenRepo.remove(userId, deviceId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/users/{id}") {
                val viewerId = call.principal<JWTPrincipal>()?.payload?.subject
                val user = userRepo.getPublicById(call.parameters["id"]!!, viewerId = viewerId)
                if (user != null) {
                    call.respond(
                        user.copy(isOnline = user.isOnline && userRepo.shouldShowOnlineTo(user.id, viewerId))
                    )
                } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 上传头像
            post("/api/users/avatar") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得更换头像/资料（与 profile 修改一致）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                if (!avatarRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("头像操作过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)?.let { parseJson<UploadAvatarRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                val avatarUrl = try {
                    com.maodouchat.server.service.FileStorageService.saveAvatar(req.base64Data, userId)
                } catch (e: IllegalArgumentException) {
                    // 不把内部校验明细回传给客户端，仅服务端日志保留上下文
                    call.application.log.warn("Avatar upload rejected for user {}: {}", userId, e.message)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("头像数据无效"))
                    return@post
                }
                val replacement = try {
                    userRepo.replaceAvatar(userId, avatarUrl)
                } catch (error: Throwable) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                    throw error
                }
                if (replacement == null) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@post
                }
                if (replacement.previousUrl != replacement.currentUrl) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("avatarUrl", avatarUrl)
                }
            )
            }

            delete("/api/users/avatar") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val replacement = userRepo.replaceAvatar(userId, null)
                if (replacement == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@delete
                }
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            // 修改资料
            put("/api/users/profile") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
                // 8.33 修复：封禁用户不得修改资料（此前仅部分写路径有检查）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val req = call.receiveBoundedText()?.let { parseJson<UpdateProfileRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
                val before = userRepo.getById(userId)
                userRepo.updateProfile(userId, name = req.name, status = req.status)
                val updated = userRepo.getById(userId)
                // 0.93：资料变更失效公开主页缓存
                before?.username?.let { cacheService.invalidateUserProfile("user_profile:$it") }
                if (updated != null) call.respond(updated)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 设置用户名（类似 @username，用于 chat.mdou.me/u/{username}）
            put("/api/users/me/username") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
                // 8.38：封禁用户不得改用户名（与头像/资料/附近位置一致）；且设置带限流防占用枚举
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                if (!userSearchRateLimiter.acquire("username:$userId", maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@put
                }
                val obj = call.receiveBoundedText()?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                val username = obj["username"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()
                // 8.40：格式非法 400、已占用 409 分离（此前一律 409，客户端无法区分参数错误与冲突）
                if (username.length !in 3..50 || !username.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("用户名格式无效（3-50 位字母/数字/_/-）"))
                }
                val result = userRepo.setUsername(userId, username)
                if (result != null) {
                    // 0.93：用户名变更失效旧/新公开主页缓存
                    cacheService.invalidateUserProfile("user_profile:$username")
                    call.respond(
                buildJsonObject {
put("ok", true)
put("username", result)
                }
            )
                } else {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("用户名不可用（已占用或格式无效）"))
                }
            }

            // 清除用户名
            delete("/api/users/me/username") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@delete }
                if (call.rejectIfSuspended(userRepo, userId)) return@delete
                userRepo.clearUsername(userId)
                call.respond(
                buildJsonObject {
put("ok", true)
                }
            )
            }

            // 获取当前用户公开信息（含用户名；8.32 一致性：公开形态不含 email 等私有字段）
            get("/api/users/me/public") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val user = userRepo.getPublicMe(userId)
                if (user != null) {
                    val publicProfileUrl = user.username?.let { "${ServerConfig.baseUrl.trimEnd('/')}/u/${it}" }
                    call.respond(
                buildJsonObject {
put("user", Json.parseToJsonElement(Json.encodeToString(user)))
put("publicProfileUrl", publicProfileUrl)
                }
            )
                } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 修改密码：成功后吊销全部刷新令牌并轮换 access token version，避免旧会话继续有效
            post("/api/users/change-password") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<ChangePasswordRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (req.oldPassword.isBlank() || !isValidPassword(req.newPassword)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("新密码至少 6 位"))
                    return@post
                }
                val ok = userRepo.changePassword(userId, req.oldPassword, req.newPassword)
                if (ok) {
                    authTokenRepo.rotateAccessTokenVersion(userId)
                    // 与 logout-all 一致：旧设备会话已废，推送 token 必须清掉，否则仍收消息/来电推送
                    pushTokenRepo.removeAllForUser(userId)
                    disconnectUserSessions(userId, "密码已修改，请重新登录")
                    call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
                } else {
                    // 403：勿用 401 — 客户端 executeWithRefresh 会把带 Authorization 的 401 当会话过期并清库
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("原密码错误", code = "WRONG_PASSWORD"))
                }
            }

            delete("/api/users/me") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val groupAvatarCandidates = groupMediaReferenceRepo.avatarUrlsForParticipant(userId)
                // 8.33 修复：删号会 bump memberRevision（含群主转让），但此前无广播，剩余成员残留成员列表
                val groupSnapshots = conversationParticipantRepo.groupMembershipSnapshotForDeletion(userId)
                val req = call.receiveBoundedText()?.let { parseJson<DeleteAccountRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@delete
                }
                if (req.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请输入当前密码"))
                    return@delete
                }
                val deactivation = userRepo.deleteAccount(userId, req.password)
                if (deactivation == null) {
                    // 403：凭证错误，非 access token 失效
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("密码错误或账号已注销", code = "WRONG_PASSWORD"))
                } else {
                    // 空会话级联删除的附件行已不在 DB；先清磁盘，再清仍挂在其他会话上的本人上传
                    deactivation.orphanedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
                    encryptedAttachmentRepo.deleteForUploader(userId).forEach(EncryptedAttachmentStorage::delete)
                    postRepo.deleteAllPostsForAuthor(userId)
                    com.maodouchat.server.service.FileStorageService.deletePostImagesForUser(userId)
                    groupAvatarCandidates
                        .filterNot(groupMediaReferenceRepo::isAvatarUrlReferenced)
                        .forEach { url ->
                            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(url)
                        }
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(deactivation.avatarUrl, userId)
                    // 与 logout-all / 改密码一致：吊销已签发的 access token（版本号）并清推送 token，
                    // 否则注销后旧 JWT 在 TTL 内仍可调用 API；推送 token 不清则已注销设备仍收消息/来电。
                    authTokenRepo.rotateAccessTokenVersion(userId)
                    pushTokenRepo.removeAllForUser(userId)
                    disconnectUserSessions(userId, "账号已注销")
                    // 8.33：注销后向各群剩余成员广播成员变更（含自动群主转让），客户端即时刷新成员列表
                    groupSnapshots.forEach { (chatId, recipients) ->
                        val remaining = recipients.filter { it != userId }
                        if (remaining.isNotEmpty()) {
                            notifyGroupRevisionChanged(
                                queryRepository = conversationQueryRepo,
                                participantRepository = conversationParticipantRepo,
                                json = json,
                                chatId = chatId,
                                reason = "MEMBER_REMOVED",
                                actorId = userId,
                                targetUserId = userId,
                                recipientIds = remaining
                            )
                        }
                    }
                    call.respond(DeleteAccountResponse(deletedAt = deactivation.deletedAt))
                }
            }
    }
}
