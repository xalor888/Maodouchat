package com.maodouchat.group

import android.app.Application
import com.maodouchat.MaodouchatApp
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.network.TokenManager

object GroupDetailAccess {

    /** 群成员状态存储（U06，`InMemoryGroupMembershipStore`，与 app 单例同一实例）。 */
    val groupMembershipStore: GroupMembershipStore
        get() = MaodouchatApp.instance.groupMembershipStore

    /** 为群详情装配 Android 侧的群消息协调器（含 Signal 协议实例）。 */
    fun groupMessagingCoordinator(application: Application): GroupMessagingCoordinator =
        createAndroidGroupMessagingCoordinator(
            app = MaodouchatApp.instance,
            signalProtocol = MaodouchatApp.instance.signalProtocol,
            tokenManager = TokenManager.getInstance(application),
        )
}
