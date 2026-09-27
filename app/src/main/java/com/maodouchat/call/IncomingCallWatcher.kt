package com.maodouchat.call

import android.app.Application
import android.content.Context
import android.util.Log
import com.maodouchat.data.repository.UserNetworkRepository
import com.maodouchat.navigation.AppNavigationEvents
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.service.CallForegroundService
import com.maodouchat.session.AppRuntime
import com.maodouchat.session.CurrentSession
import com.maodouchat.telecom.MaodouchatConnectionService
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 来电观察器的非 ui 执行层（U02 延伸：自 `ui/navigation/CallNavigation.kt` 的
 * `IncomingCallObserver` 收口——该文件的 9 处 `MaodouchatApp` 直连命中归零）。
 *
 * 原实现把三件事全写在一个 `@Composable` 里：REST 轮询待处理 offer、
 * FCM/系统通知唤醒事件、WS 通话信令流。收进这里后语义逐字不变：
 * - 会话世代用 [AppRuntime.currentSessionGeneration]（世代是实时映射，非常量）；
 * - 实时连接拉起走 [AppRuntime.ensureRealtimeConnected]；
 * - 来电唤醒通道走 [AppNavigationEvents]（收事件/消费事件）；
 * - 实时事件分发器走 [AppRuntime.realtimeDispatcherOrNull]（非本应用实例 → 不订阅）；
 * - 未接来电墓碑走 [MissedCallRecorder]（它内部只用 `applicationContext`，
 *   调用方不再需要把 context 强转成 app 单例）；
 * - offer 的取舍决策接线到 [PolledIncomingBatchPolicy]（终端信令仍按原内联顺序
 *   逐个执行：re-peek → 命中即清 pending → hang-up 记墓碑；offer 半用
 *   [PolledIncomingBatchPolicy.decide] 在终端处理完后的新快照上重算，
 *   与原「终端循环之后再 peek」的顺序一致）。
 *
 * ui 侧只剩一个薄的 `@Composable` 启动器：构造本类（导航动作以 lambda 注入，
 * navController 不进 call 包）并在 `LaunchedEffect` 里 `start(this)`。
 */
class IncomingCallWatcher(
    private val appContext: Context,
    private val navigateToIncomingCall: () -> Unit,
) {

    /** 启动三个收集器：冷启动轮询、来电唤醒、WS 通话信令。调用方在组合离开时取消 scope。 */
    fun start(scope: CoroutineScope) {
        // Cold start / resume: pull any server-side pending offers
        scope.launch {
            // Wait briefly for token if login just completed
            var attempts = 0
            while (!CurrentSession.hasSession() && attempts < 20) {
                delay(250)
                attempts++
            }
            pollPendingOffers()
        }

        // FCM / system notification tap → re-poll (IncomingCallObserver may already be alive)
        scope.launch {
            AppNavigationEvents.incomingCallWakeEvents().collect { wake ->
                if (AppNavigationEvents.incomingCallWakeIsStale(wake)) {
                    return@collect
                }
                // 8.56：系统 Telecom「接听」唤醒 → 带 autoAnswer 进入轮询，命中的来电自动接听
                pollPendingOffers(preferCallId = wake.callId, autoAnswer = wake.autoAnswer)
                AppNavigationEvents.consumeIncomingCallWake(wake)
            }
        }

        scope.launch {
            val signalOwnerUserId = CurrentSession.ownerUserId()
            val application = appContext as? Application ?: return@launch
            val dispatcher = AppRuntime.realtimeDispatcherOrNull(application) ?: return@launch
            dispatcher.callSignalingEvents.collect { event ->
                // Drop buffered signaling after logout / account switch.
                if (
                    signalOwnerUserId.isBlank() ||
                    !BackgroundSessionGate.mayContinue(
                        expectedUserId = signalOwnerUserId,
                    )
                ) {
                    return@collect
                }
                val t = event.type.lowercase()
                // 响铃中 hang-up/busy/reject：清 pending，避免 30s 内继续响
                if (CallOfferSelector.isTerminalType(event.type)) {
                    if (event.callId.isNotBlank()) {
                        CallNotificationService.cancelIncomingCall(appContext, event.callId)
                        val pending = IncomingCallCoordinator.peekPending()
                        val matchedPending = pending?.takeIf {
                            it.callId == event.callId ||
                                (it.callId.isBlank() && it.contactId == event.fromUserId)
                        }
                        if (matchedPending != null) {
                            IncomingCallCoordinator.clear()
                            // Caller gave up while we still had the offer → missed row.
                            // busy/reject as terminal for our pending is unusual as callee;
                            // hang-up is the common cancel. Still record for hang-up only.
                            if (t == "hang-up") {
                                val ring = matchedPending
                                val hangupOwnerUserId = signalOwnerUserId
                                launch {
                                    try {
                                        if (!BackgroundSessionGate.mayContinue(
                                            expectedUserId = hangupOwnerUserId,
                                        )
                                        ) {
                                            return@launch
                                        }
                                        MissedCallRecorder.recordRingTimeout(
                                            context = appContext,
                                            signalingCallId = event.callId.ifBlank { ring.callId },
                                            fromUserId = ring.contactId.ifBlank { event.fromUserId },
                                            callerName = ring.contactName,
                                            isVideo = ring.callType == CallType.VIDEO,
                                            isGroup = ring.groupId.isNotBlank(),
                                        )
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        Log.w(
                                            "IncomingCallWatcher",
                                            "missed-call on peer hang-up failed",
                                            error
                                        )
                                    }
                                }
                            }
                        }
                        // Ask CallViewModel to tear down if this call is prepared/active
                        // (RINGING may not have started the foreground service yet).
                        CallActionBus.requestHangUp(event.callId, notifyPeer = false)
                    }
                    return@collect
                }
                if (event.type == "offer" && CallOfferSelector.isDirectRingOffer(event.groupId, event.groupInvite)) {
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = signalOwnerUserId,
                    )
                    ) {
                        return@collect
                    }
                    val token = CurrentSession.snapshot().token.orEmpty()
                    // 已有 pending/活跃通话：对后来的 offer 回 busy，避免静默覆盖
                    val existingPending = IncomingCallCoordinator.peekPending()
                    val activeId = CallForegroundService.getActiveCallId()
                    // 8.35：同一 callId 已 pending（WS at-least-once 重投 / FCM 轮询先行送达）→ 幂等跳过
                    if (existingPending != null && existingPending.callId.isNotBlank() &&
                        existingPending.callId == event.callId
                    ) {
                        return@collect
                    }
                    if ((existingPending != null && existingPending.callId != event.callId) ||
                        (activeId.isNotBlank() && activeId != event.callId)
                    ) {
                        if (
                            token.isNotBlank() &&
                            BackgroundSessionGate.mayContinue(
                                expectedUserId = signalOwnerUserId,
                            )
                        ) {
                            val liveToken = token
                            WebRTCSignaling.sendViaRest(
                                liveToken,
                                event.fromUserId,
                                "busy",
                                "",
                                event.callId,
                                event.groupId,
                                event.groupMemberIds
                            )
                        }
                        return@collect
                    }
                    // 立即响铃/跳转：先把 offer 推给 IncomingCallCoordinator 让 UI 弹出来
                    resolveCallerAndNavigate(
                        event.fromUserId,
                        event.payload,
                        event.callId,
                        event.groupId,
                        event.groupMemberIds,
                        token
                    )
                    val observedPending = IncomingCallCoordinator.peekPending()
                    // 独立计时，不能阻塞 WebSocket collector 继续处理后续 offer。
                    // CallViewModel also records on RINGING timeout (same stable callId →
                    // REPLACE). This path covers coordinator still holding the offer when
                    // the VM never prepared / already cleared after peer hang-up.
                    launch {
                        delay(30_000L)
                        val stillPending = IncomingCallCoordinator.peekPending()
                        if (!MissedCallTimeoutPolicy.shouldRecordMissed(
                                observedPending,
                                stillPending
                            )
                        ) {
                            return@launch
                        }
                        val signalingCallId = event.callId.ifBlank { stillPending?.callId.orEmpty() }
                        // Clear coordinator so IncomingCallRoute pops and a later offer can ring.
                        IncomingCallCoordinator.clear()
                        // 8.49 修复：振铃超时同步销毁 Telecom Connection——此前唯一销毁入口是
                        // CallViewModel.endCall，锁屏/后台来电 Activity 被回收时 VM 不在，
                        // 系统通话 UI 无限期 RINGING（35s 自毁兜底之外的即刻路径）
                        if (signalingCallId.isNotBlank()) {
                            MaodouchatConnectionService.finishConnection(signalingCallId)
                        }
                        // If CallViewModel is still RINGING for this call, end without re-notifying
                        // peer (local timeout already implies no answer; VM timeout may race).
                        if (signalingCallId.isNotBlank()) {
                            CallActionBus.requestHangUp(
                                signalingCallId,
                                notifyPeer = false
                            )
                        }
                        val liveOwner = CurrentSession.ownerUserId()
                        val resolvedName = if (
                            token.isNotBlank() &&
                            liveOwner.isNotBlank() &&
                            BackgroundSessionGate.mayContinue(
                                expectedUserId = liveOwner,
                            )
                        ) {
                            val liveToken = token
                            UserNetworkRepository().users(liveToken).getOrNull()?.find { it.id == event.fromUserId }?.name
                        } else {
                            null
                        }
                        val displayName = stillPending?.contactName
                            ?.takeIf { it.isNotBlank() }
                            ?: resolvedName
                            ?: event.fromUserId
                        val isVideo = CallType.detectFromSdp(event.payload) == CallType.VIDEO
                        try {
                            MissedCallRecorder.recordRingTimeout(
                                context = appContext,
                                signalingCallId = signalingCallId,
                                fromUserId = event.fromUserId,
                                callerName = displayName,
                                isVideo = isVideo,
                                isGroup = event.groupId.isNotBlank(),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("IncomingCallWatcher", "missed-call record failed", error)
                        }
                    }
                }
            }
        }
    }

    suspend fun pollPendingOffers(preferCallId: String = "", autoAnswer: Boolean = false) {
        val token = CurrentSession.snapshot().token.orEmpty()
        if (token.isBlank()) return
        // Capture epoch so logout/account switch mid-fetch cannot apply offers to the next owner.
        val pollGeneration = AppRuntime.currentSessionGeneration
        val pollUserId = CurrentSession.ownerUserId()
        AppRuntime.ensureRealtimeConnected(appContext)
        WebRTCSignaling.fetchPending(token, offersOnly = true).onSuccess { messages ->
            if (
                pollGeneration != AppRuntime.currentSessionGeneration ||
                !BackgroundSessionGate.mayContinue(
                    expectedUserId = pollUserId,
                )
            ) {
                return@onSuccess
            }
            // 先处理终端信令：清 FCM/系统来电通知与本地 pending，避免幽灵来电
            // 若本机正有活跃通话，必须转发给 CallViewModel（offersOnly 已消费 hang-up 行）
            // ——逐字沿用原内联循环语义：逐个 re-peek，命中 pending 即清；
            // 只有「对方挂断」才记未接墓碑。
            messages.filter { CallOfferSelector.isTerminalType(it.type) }.forEach { terminal ->
                if (terminal.callId.isNotBlank()) {
                    CallNotificationService.cancelIncomingCall(appContext, terminal.callId)
                    val pending = IncomingCallCoordinator.peekPending()
                    if (pending != null && pending.callId == terminal.callId) {
                        IncomingCallCoordinator.clear()
                        // REST poll may surface hang-up before/without live WS; record missed.
                        if (terminal.type.equals("hang-up", ignoreCase = true)) {
                            try {
                                MissedCallRecorder.recordRingTimeout(
                                    context = appContext,
                                    signalingCallId = terminal.callId.ifBlank { pending.callId },
                                    fromUserId = pending.contactId.ifBlank { terminal.fromUserId },
                                    callerName = pending.contactName,
                                    isVideo = pending.callType == CallType.VIDEO,
                                    isGroup = pending.groupId.isNotBlank(),
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(
                                    "IncomingCallWatcher",
                                    "missed-call on polled hang-up failed",
                                    error
                                )
                            }
                        }
                    }
                    // Always bus hang-up so prepared RINGING/CONNECTED VM tears down
                    // (foreground service may not be up yet during pure ring UI).
                    CallActionBus.requestHangUp(terminal.callId, notifyPeer = false)
                }
            }
            // offer 取舍接线到 PolledIncomingBatchPolicy 的决策：
            // 在终端处理完后的新快照上重算（与原「终端循环之后再 peek」的顺序一致）；
            // decide 的 terminals 半仅用于其内部的 terminatedCallIds，不再重复执行。
            val decision = PolledIncomingBatchPolicy.decide(
                messages = messages,
                preferCallId = preferCallId,
                existingPending = IncomingCallCoordinator.peekPending(),
                nowMs = System.currentTimeMillis(),
            )
            // FCM 指定 callId 已被终端覆盖 / 库中已无其 offer（幽灵响铃）：清系统通知
            if (preferCallId.isNotBlank() && decision.cancelPreferCallId) {
                CallNotificationService.cancelIncomingCall(appContext, preferCallId)
            }
            val primary = decision.primary ?: return@onSuccess
            // 双通道去重（8.35）已在决策内：WS 已送达的同 callId 不再重复导航/派发
            if (decision.shouldNavigate) {
                resolveCallerAndNavigate(
                    primary.fromUserId,
                    primary.payload,
                    primary.callId,
                    primary.groupId,
                    primary.groupMemberIds,
                    token,
                    autoAnswer = autoAnswer
                )
            }
            decision.busyReplies.forEach { message ->
                WebRTCSignaling.sendViaRest(
                    token,
                    message.fromUserId,
                    "busy",
                    "",
                    message.callId,
                    message.groupId,
                    message.groupMemberIds
                )
            }
        }
    }

    private suspend fun resolveCallerAndNavigate(
        fromUserId: String,
        offerSdp: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        token: String,
        autoAnswer: Boolean = false,
    ) {
        // 尝试解析来电者名称，避免显示原始 UUID
        val ownerUserId = CurrentSession.ownerUserId()
        val callerName = if (
            token.isNotBlank() &&
            ownerUserId.isNotBlank() &&
            BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            val liveToken = token
            UserNetworkRepository().users(liveToken).getOrNull()
                ?.find { it.id == fromUserId }
                ?.name
        } else null
        // getUsers can outlive switch — drop before parking offer / navigating for next owner.
        if (
            ownerUserId.isBlank() ||
            !BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        ) {
            return
        }
        val displayName = callerName ?: fromUserId

        val callType = CallType.detectFromSdp(offerSdp)
        IncomingCallCoordinator.setPending(
            IncomingCallCoordinator.PendingIncomingCall(
                contactId = fromUserId,
                contactName = displayName,
                callType = callType,
                offerSdp = offerSdp,
                callId = callId,
                groupId = groupId,
                groupMemberIds = groupMemberIds,
                autoAnswer = autoAnswer,
            )
        )
        // P03：Telecom 来电派发经 CallSystemIntegration；失败时静默回退到应用内 IncomingCallRoute
        CallSystemIntegration(appContext).placeIncomingCall(
            callerName = displayName,
            callId = callId,
            isVideo = callType == CallType.VIDEO,
        )
        navigateToIncomingCall()
    }
}
