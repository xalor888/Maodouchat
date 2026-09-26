package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maodouchat.ui.component.ParticleState

/**
 * G335：**发送前的待确认项** —— 选完图/视频之后、真正发出去之前的那一小段状态。
 *
 * 归成一族的理由：这四个状态描述同一件事的不同阶段，且**必须一起变**：
 * `pendingViewOnce`/`pendingSpoiler` 是「本次发送的两个开关」（选图前设置、发送后复位），
 * `pendingImageConfirm`/`pendingVideoConfirm` 是「已经选好、等用户点确认的那一项」。
 * 它们原先散在 Route 的两处（开关在 composer 附近、待确认项在 picker 接线附近），
 * 读的人得来回找才能确认「这两个开关在哪被复位」。
 *
 * 与 `ChatDetailMessageActionState` 一样是普通持有类：这四项**都不该跨进程重建存活**
 * （重建后弹回一个「待确认的图」而 URI 权限可能已失效，反而是坏体验）。
 */
internal class ChatDetailSendPendingState {
    /** 下一次发送是否「阅后即焚」。 */
    var viewOnce by mutableStateOf(false)

    /** 下一次发送是否「剧透」（模糊遮罩）。 */
    var spoiler by mutableStateOf(false)

    /** 图片：已选好、等用户在预览里确认的那一项。 */
    var imageConfirm by mutableStateOf<PendingImageSend?>(null)

    /** 视频：同上（0.69 起视频也走预览确认）。 */
    var videoConfirm by mutableStateOf<PendingImageSend?>(null)

    /**
     * 选到图/视频后统一做的三件事：记下待确认项、复位两个开关。
     * 原先这段在 Route 里被抄了两遍（图片/视频各一次），收进来后只此一份。
     */
    fun onPicked(uri: android.net.Uri, isVideo: Boolean) {
        val pending = PendingImageSend(uri, viewOnce, spoiler)
        if (isVideo) videoConfirm = pending else imageConfirm = pending
        viewOnce = false
        spoiler = false
    }
}

/**
 * G335：**粒子删除动效**的三个状态（哪条消息在动、动完做什么、粒子坐标表）。
 *
 * 归成一族：它们是同一个动画的三段生命周期，任何一段被单独重置都会留下「消息不见了但动效没播完」
 * 或「动效播完但消息还在」的中间态。也不该跨进程存活（进程重建后动画本来就该重新开始）。
 */
internal class ChatDetailParticleState {
    /** 正在播放粒子动效的消息 id（null = 没有动效在播）。 */
    var animatingMessageId by mutableStateOf<String?>(null)

    /** 动效播完后要对这条消息做的事（删除/撤回）。 */
    var action by mutableStateOf<ParticleAction?>(null)

    /** 粒子坐标表（由 `startParticleEffect` 按气泡位置生成）。 */
    var states by mutableStateOf<List<ParticleState>>(emptyList())

    /** 开始一段动效。 */
    fun start(messageId: String, action: ParticleAction, states: List<ParticleState>) {
        this.animatingMessageId = messageId
        this.action = action
        this.states = states
    }

    /** 复位（动效播完或被打断）。 */
    fun clear() {
        animatingMessageId = null
        action = null
        states = emptyList()
    }
}

/**
 * 8.48：**群禁言到期重组触发器**——禁言提示条只读 `state.myMutedUntil`（ViewModel 层），
 * 但禁言到期那一刻没有任何状态变化，重组不会发生，提示条就赖在那里不消失。
 *
 * 解法：到期时刻写一次这个 tick，读它的那个作用域被动重组，提示条重新计算
 * `remaining` 发现已过期就消失了。它不该跨进程存活（重建后提示条按
 * `state.myMutedUntil` 重新算，tick 值本身无意义），所以不做 Saver，原来就是
 * 普通 `remember {}`。
 */
internal class ChatDetailMuteExpiryState {
    /** 禁言到期写入的时间戳；读它只为建立重组依赖。 */
    var muteTick by mutableLongStateOf(0L)

    /** 禁言到期时调用（重组触发）。 */
    fun markExpired() {
        muteTick = System.currentTimeMillis()
    }
}
