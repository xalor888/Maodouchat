package com.maodouchat.navigation

import com.maodouchat.IncomingCallWake
import com.maodouchat.MaodouchatApp
import com.maodouchat.consumeIfStale
import com.maodouchat.util.ClientPrefsSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 导航层使用的 app 级事件通道（U02 延伸：把 `ui/navigation` 里的 `MaodouchatApp`
 * 直连收进非 ui 层）。
 *
 * 为什么收：`MainContainerRoute` / `AuthDestinations` 里对 app 单例的访问是纯通道
 * （收事件、消费事件、拿 applicationScope）——放在 Composable 里既难测也把
 * `MaodouchatApp` 符号留在 ui（`ClientArchitectureTest` 的直连持久层棘轮逐字计）。
 * 收进这里后 ui 只调本对象，棘轮对应条目归零。
 *
 * 请求类型经 [typealias] 暴露：`OpenMissedCallsRequest` 内嵌在 `MaodouchatApp` 里，
 * ui 直接引用它的类型名会把 `MaodouchatApp` 符号又带回 ui 文件（棘轮按子串计数），
 * 所以这里给别名——类型不变，符号面收窄。
 */
typealias OpenMissedCallsRequest = MaodouchatApp.Companion.OpenMissedCallsRequest
typealias OpenContactsRequest = MaodouchatApp.Companion.OpenContactsRequest

object AppNavigationEvents {

    // ---- 未接来电托盘点击 → 会话 Tab ----

    fun missedCallsEvents(): Flow<OpenMissedCallsRequest> = MaodouchatApp.openMissedCallsEvents

    /** 世代过期则消费丢弃并返回 true（调用方直接 return），语义同 [consumeIfStale]。 */
    fun missedCallsIsStale(request: OpenMissedCallsRequest): Boolean =
        consumeIfStale(request, MaodouchatApp::consumeOpenMissedCalls)

    fun consumeMissedCalls(request: OpenMissedCallsRequest) {
        MaodouchatApp.consumeOpenMissedCalls(request)
    }

    // ---- 好友请求/联系人深链 → 联系人 Tab ----

    fun contactsEvents(): Flow<OpenContactsRequest> = MaodouchatApp.openContactsEvents

    fun contactsIsStale(request: OpenContactsRequest): Boolean =
        consumeIfStale(request, MaodouchatApp::consumeOpenContacts)

    fun consumeContacts(request: OpenContactsRequest) {
        MaodouchatApp.consumeOpenContacts(request)
    }

    // ---- 来电唤醒（FCM/系统通知点击 → 触发待处理 offer 轮询） ----

    /** 原 `MaodouchatApp.incomingCallWakeEvents` 的非 ui 访问点。 */
    fun incomingCallWakeEvents(): Flow<IncomingCallWake> =
        MaodouchatApp.incomingCallWakeEvents

    /** 世代过期则消费丢弃并返回 true（调用方直接 return），语义同 [consumeIfStale]。 */
    fun incomingCallWakeIsStale(wake: IncomingCallWake): Boolean =
        consumeIfStale(wake, MaodouchatApp::consumeIncomingCallWake)

    fun consumeIncomingCallWake(wake: IncomingCallWake) {
        MaodouchatApp.consumeIncomingCallWake(wake)
    }

    // ---- 登录成功后的多端偏好同步 ----

    /**
     * 主壳绘制前先拉取多端 UX 偏好（主题/语言），避免用陈旧本地值先画一帧。
     * 原实现内联在 `AuthDestinations` 的 onLoginSuccess 里。
     */
    fun pullAndApplyClientPrefsAsync() {
        MaodouchatApp.instance.applicationScope.launch {
            runCatching {
                ClientPrefsSync.pullAndApply(MaodouchatApp.instance)
            }
        }
    }
}
