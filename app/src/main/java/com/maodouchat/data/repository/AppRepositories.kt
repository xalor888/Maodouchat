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
}
