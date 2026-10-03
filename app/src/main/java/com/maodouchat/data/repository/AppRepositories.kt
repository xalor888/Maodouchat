package com.maodouchat.data.repository

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.dao.UserDao

object AppRepositories {

    val messageSearch: MessageSearchRepository
        get() = MessageSearchRepository(MaodouchatApp.instance.database)

    val chats: ChatRepository
        get() = MaodouchatApp.instance.let { app ->
            ChatRepository(app.database.chatDao(), app.database.userDao())
        }

    val users: UserRepository
        get() = UserRepository(MaodouchatApp.instance.database.userDao())

    /**
     * 裸 UserDao（通讯录实时同步协调器直接写 Room Flow 源用；其余调用方优先用 [users]）。
     * 与 [users] 是同一数据库实例的同一 DAO，无双重来源。
     */
    val userDao: UserDao
        get() = MaodouchatApp.instance.database.userDao()

    val chatLocks: ChatLockRepository
        get() = ChatLockRepository(MaodouchatApp.instance.database.chatLockDao())

    val messages: LocalMessageStore
        get() = MaodouchatApp.instance.let { LocalMessageStore(it.database.messageDao(), it.database) }

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
