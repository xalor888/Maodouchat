package com.maodouchat.group

import android.app.Application
import com.maodouchat.MaodouchatApp
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.network.TokenManager

/**
 * 群详情页的非 ui 访问点（U02 延伸：自 `ui/screen/chatdetail/GroupDetailViewModel`
 * 的 app 单例直连收口——该 VM 的两处棘轮命中即 `import MaodouchatApp` 与
 * `application as MaodouchatApp`，`app` 符号贯穿了群消息协调器工厂的装配）。
 *
 * `createAndroidGroupMessagingCoordinator` 的工厂签名要求传 app 本体（内部要
 * `database.messagingV2Dao()` / `attachmentTransferDao()` / `senderKeyRetryManager`），
 * ui 不该把 app 符号引进来——本对象在非 ui 层代为装配。
 * `messaging/v2/` 的工厂需要 TokenManager 实例（它自己读会话），属非 ui 层依赖。
 */
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
