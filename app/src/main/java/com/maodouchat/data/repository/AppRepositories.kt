package com.maodouchat.data.repository

import com.maodouchat.MaodouchatApp

/**
 * 应用级仓库访问点（U02 延伸：自 `ui/screen/chatlist/GlobalSearchScreen` 的 app 单例
 * + `app.database` 直连收口）。
 *
 * 为什么放在 data/repository：这些仓库本来就是「app 数据库之上的薄封装」（无状态、
 * 只包 DAO），ui 只是**拿不到**它们的应用级入口，才被迫 `application as MaodouchatApp`
 * 后自取 `app.database`。把入口收到非 ui 层后，ui 直连持久层棘轮对应命中归零，
 * 且以后要给「应用级仓库」加缓存/开关也只有一处。
 *
 * 构造是廉价的（仓库不持状态），每次 get 新建与调用点原来的 `XxxRepository(db)` 等价。
 */
object AppRepositories {

    val messageSearch: MessageSearchRepository
        get() = MessageSearchRepository(MaodouchatApp.instance.database)

    val chats: ChatRepository
        get() = MaodouchatApp.instance.let { app ->
            ChatRepository(app.database.chatDao(), app.database.userDao())
        }

    val users: UserRepository
        get() = UserRepository(MaodouchatApp.instance.database.userDao())

    val chatLocks: ChatLockRepository
        get() = ChatLockRepository(MaodouchatApp.instance.database.chatLockDao())

    /** AI 任务仓库（需要调用方的 Application 做提醒调度；生产环境即 app 单例）。 */
    fun aiTasks(application: android.app.Application): AiTaskRepository =
        AiTaskRepository(MaodouchatApp.instance.database.aiTaskDao(), application)

    /**
     * 单行的会话实体读取（与 ui 原先 `app.database.chatDao().getChatById` 同一条查询、
     * 同一成本）——`ChatRepository.getChatById` 会顺带全表读 users 装配参与者，在
     * 「只要一个名字」的场景里代价过高，故此处保持窄口径。
     */
    suspend fun chatEntityOrNull(chatId: String): com.maodouchat.data.local.entity.ChatEntity? =
        MaodouchatApp.instance.database.chatDao().getChatById(chatId)
}
