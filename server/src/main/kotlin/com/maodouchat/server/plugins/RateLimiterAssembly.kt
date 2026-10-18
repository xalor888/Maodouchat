package com.maodouchat.server.plugins

import com.maodouchat.server.service.LoginAttemptGate

// 路由层限流器的一次性装配：构造参数即策略，Routing.kt 只做接线。
internal class RateLimiterAssembly(
    val preKeyFetchTracker: BoundedRateLimiter,
    val sendCodeRateLimiter: BoundedRateLimiter,
    val sendCodeIpRateLimiter: BoundedRateLimiter,
    val loginIpRateLimiter: BoundedRateLimiter,
    val loginEmailRateLimiter: BoundedRateLimiter,
    val loginGate: LoginAttemptGate,
    val friendRequestRateLimiter: BoundedRateLimiter,
    val userSearchRateLimiter: BoundedRateLimiter,
    val publicProfileRateLimiter: BoundedRateLimiter,
    val nearbyUpdateRateLimiter: BoundedRateLimiter,
    val nearbyQueryRateLimiter: BoundedRateLimiter,
    val reactionRateLimiter: BoundedRateLimiter,
    val botCreateRateLimiter: BoundedRateLimiter,
    val botTokenRateLimiter: BoundedRateLimiter,
    val botSendRateLimiter: BoundedRateLimiter,
    val aiRateLimiter: BoundedRateLimiter,
    val reportRateLimiter: BoundedRateLimiter,
    val avatarRateLimiter: BoundedRateLimiter,
    val createChatRateLimiter: BoundedRateLimiter,
    val postRateLimiter: BoundedRateLimiter,
    val postImageRateLimiter: BoundedRateLimiter,
    val commentRateLimiter: BoundedRateLimiter,
    val postLikeRateLimiter: BoundedRateLimiter,
    val commentLikeRateLimiter: BoundedRateLimiter,
    val totpManageRateLimiter: BoundedRateLimiter,
)

internal fun buildRateLimiterAssembly(): RateLimiterAssembly {
    val preKeyFetchTracker = BoundedRateLimiter(maxBuckets = 20_000)
    // 邮箱验证码发送频率限制：每邮箱每分钟最多 3 次
    val sendCodeRateLimiter = BoundedRateLimiter()
    // 邮箱验证码发送 IP 级限制：每 IP 每分钟最多 20 次（防邮件轰炸）
    val sendCodeIpRateLimiter = BoundedRateLimiter()
    // 登录+注册 IP 级限制：每 IP 每分钟最多 10 次（防暴破）
    val loginIpRateLimiter = BoundedRateLimiter()
    // 登录/注册/重置 按账号(email)限制：关闭「多源 IP 分布式爆破同一账号」的绕过路径
    val loginEmailRateLimiter = BoundedRateLimiter()
    // Single-account consecutive login failure lockout (B02 LoginAttemptGate).
    val loginGate = LoginAttemptGate()
    // 好友申请按发起用户限流：防止单用户向同/多目标狂发申请（通知轰炸/骚扰）
    val friendRequestRateLimiter = BoundedRateLimiter()
    // 用户目录查询按用户限流：防止整库抓取/枚举（/api/users 空 q 返回全量、/api/users/search 可遍历）
    val userSearchRateLimiter = BoundedRateLimiter()
    // 全局消息搜索按用户限流：底层 LIKE 全表扫描成本高，10/min 防止搜索 DoS
    // 0.98：公开主页接口按 IP 限流（/api/public/profile、/u/{username} 匿名可被枚举）
    val publicProfileRateLimiter = BoundedRateLimiter()
    // 附近的人更新/查询按用户限流：10/min 更新 + 30/min 查询，防高频 DB 写与 haversine 计算
    val nearbyUpdateRateLimiter = BoundedRateLimiter()
    val nearbyQueryRateLimiter = BoundedRateLimiter()
    // 表情回应按用户限流：每次请求全群 fanout，60/min 防帧风暴
    val reactionRateLimiter = BoundedRateLimiter()
    // bot 创建/轮换 token 按用户限流 + bot 发消息按 bot 限流：防 churn 与 fanout 风暴
    val botCreateRateLimiter = BoundedRateLimiter()
    val botTokenRateLimiter = BoundedRateLimiter()
    val botSendRateLimiter = BoundedRateLimiter()
    val aiRateLimiter = BoundedRateLimiter()
    // 举报提交频率限制：每用户每分钟最多 5 次，防止刷爆审核队列（骚扰/审核资源耗尽）
    val reportRateLimiter = BoundedRateLimiter()
    // 头像上传频率限制：每用户每分钟最多 10 次，防止头像对象高频 churn / 存储放大
    val avatarRateLimiter = BoundedRateLimiter()
    // 创建聊天频率限制：每用户每分钟最多 20 次，防止群 spam / 聊天枚举
    val createChatRateLimiter = BoundedRateLimiter()
    // 动态发布频率限制：每用户每分钟最多 20 次，防止 feed spam / 存储放大
    val postRateLimiter = BoundedRateLimiter()
    // 动态图片上传频率限制：每用户每分钟最多 10 次，防止存储放大（与头像同量级）
    val postImageRateLimiter = BoundedRateLimiter()
    // 动态评论频率限制：每用户每分钟最多 30 次，防止评论洪水 / 通知轰炸作者
    val commentRateLimiter = BoundedRateLimiter()
    val postLikeRateLimiter = BoundedRateLimiter()
    /** 1.83：评论点赞独立限流（与动态点赞预算隔离）。 */
    val commentLikeRateLimiter = BoundedRateLimiter()
    val totpManageRateLimiter = BoundedRateLimiter()
    return RateLimiterAssembly(
        preKeyFetchTracker = preKeyFetchTracker,
        sendCodeRateLimiter = sendCodeRateLimiter,
        sendCodeIpRateLimiter = sendCodeIpRateLimiter,
        loginIpRateLimiter = loginIpRateLimiter,
        loginEmailRateLimiter = loginEmailRateLimiter,
        loginGate = loginGate,
        friendRequestRateLimiter = friendRequestRateLimiter,
        userSearchRateLimiter = userSearchRateLimiter,
        publicProfileRateLimiter = publicProfileRateLimiter,
        nearbyUpdateRateLimiter = nearbyUpdateRateLimiter,
        nearbyQueryRateLimiter = nearbyQueryRateLimiter,
        reactionRateLimiter = reactionRateLimiter,
        botCreateRateLimiter = botCreateRateLimiter,
        botTokenRateLimiter = botTokenRateLimiter,
        botSendRateLimiter = botSendRateLimiter,
        aiRateLimiter = aiRateLimiter,
        reportRateLimiter = reportRateLimiter,
        avatarRateLimiter = avatarRateLimiter,
        createChatRateLimiter = createChatRateLimiter,
        postRateLimiter = postRateLimiter,
        postImageRateLimiter = postImageRateLimiter,
        commentRateLimiter = commentRateLimiter,
        postLikeRateLimiter = postLikeRateLimiter,
        commentLikeRateLimiter = commentLikeRateLimiter,
        totpManageRateLimiter = totpManageRateLimiter,
    )
}
