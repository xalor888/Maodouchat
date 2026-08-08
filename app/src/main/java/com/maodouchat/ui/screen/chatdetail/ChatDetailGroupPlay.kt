package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 群玩法发送逻辑（骰子/签到/投票/抽奖/真心话等 140+ 个游戏）。
 *
 * 以扩展函数形式挂在 [ChatDetailViewModel] 上，与 UI 调用点（viewModel.sendXxx()）
 * 语法完全一致；从 ChatDetailViewModel.kt 拆分而来，行为不变。
 */
internal fun ChatDetailViewModel.sendCheckIn() {
    if (!requireGroupPlay()) return
    val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    // Lightweight local streak counter keyed by chat+day in prefs is optional; send daily check-in notice.
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCheckIn(1, label) + " · $dayKey"
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendLuckyDraw() {
    val chat = _uiState.value.chat
    if (chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return
    }
    val members = chat.participants.filter { it.id != currentUserId && it.id.isNotBlank() }
    if (members.isEmpty()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_lucky_empty)) }
        return
    }
    val target = members.random()
    val picker = tokenManager.getUserId()?.take(8) ?: "me"
    val label = target.displayName.ifBlank { target.name }.ifBlank { target.id.take(8) }
    val content = com.maodouchat.util.GroupPlayPolicy.formatLuckyDraw(picker, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendDice(sides: Int = 6) {
    if (!requireGroupPlay()) return
    val value = com.maodouchat.util.GroupPlayPolicy.rollDice(sides)
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDiceMessage(value, sides, label)
    // Send as markdown/text system-like body still E2EE encrypted as TEXT
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendReactionRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatReactionRace(
        com.maodouchat.util.GroupPlayPolicy.randomRaceToken(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendNumberBomb() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (secret, max) = com.maodouchat.util.GroupPlayPolicy.rollNumberBomb()
    val content = com.maodouchat.util.GroupPlayPolicy.formatNumberBomb(secret, max, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWordChain() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWordChain(
        com.maodouchat.util.GroupPlayPolicy.randomWordSeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRockPaperScissors() {
    if (_uiState.value.chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return
    }
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRps(
        com.maodouchat.util.GroupPlayPolicy.rollRps(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTruthPrompt() {
    if (_uiState.value.chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return
    }
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTruthPrompt(label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendAnonBox(text: String) {
    if (_uiState.value.chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return
    }
    val body = text.trim()
    if (body.isBlank()) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_anon_empty)) }
        return
    }
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatAnonBox(label, body)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWouldYouRather() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (a, b) = com.maodouchat.util.GroupPlayPolicy.randomWouldPair()
    val content = com.maodouchat.util.GroupPlayPolicy.formatWouldYouRather(a, b, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiRain() {
    if (_uiState.value.chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return
    }
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiRain(label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTwoTruthsOneLie() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTwoTruthsOneLie(
        "I once lived abroad",
        "I can cook five cuisines",
        "I have a pet dragon",
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendGroupQuiz() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (q, ans, opts) = com.maodouchat.util.GroupPlayPolicy.randomQuiz()
    val content = com.maodouchat.util.GroupPlayPolicy.formatQuiz(q, ans, opts, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCoinFlip() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCoinFlip(
        com.maodouchat.util.GroupPlayPolicy.flipCoin(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendFunRedPacket() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val amounts = listOf("8.88", "6.66", "1.00", "66", "lucky")
    val content = com.maodouchat.util.GroupPlayPolicy.formatRedPacketJoke(amounts.random(), label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

/**
 * Telegram-style inline keyboard callback: notify the bot that owns [botUserId]
 * with callback_query (webhook / getUpdates).
 */
internal fun ChatDetailViewModel.sendBotCallback(messageId: String, botUserId: String, callbackData: String) {
    val data = callbackData.trim().take(128)
    if (data.isBlank() || messageId.isBlank() || botUserId.isBlank()) return
    viewModelScope.launch {
        try {
            val tok = tokenManager.getToken().orEmpty()
            if (tok.isBlank()) return@launch
            val chatId = _uiState.value.chat?.id ?: return@launch
            val ok = ApiService.postBotCallback(
                token = tok,
                chatId = chatId,
                messageId = messageId,
                botUserId = botUserId,
                callbackData = data
            ).getOrDefault(false)
            if (!ok) {
                _uiState.update { it.copy(groupEncryptionWarning = "callback failed") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(groupEncryptionWarning = e.message?.take(120) ?: "callback failed")
            }
        }
    }
}

internal fun ChatDetailViewModel.sendCharades() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCharades(
        com.maodouchat.util.GroupPlayPolicy.randomCharadesPrompt(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendNumberGuess() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (secret, max) = com.maodouchat.util.GroupPlayPolicy.rollNumberGuess()
    val content = com.maodouchat.util.GroupPlayPolicy.formatNumberGuess(secret, max, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiBingo() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBingo(
        com.maodouchat.util.GroupPlayPolicy.randomBingoBoard(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRiddle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (q, a) = com.maodouchat.util.GroupPlayPolicy.randomRiddle()
    val content = com.maodouchat.util.GroupPlayPolicy.formatRiddle(q, a, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendImpostor() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val words = listOf("apple", "rocket", "coffee", "panda", "subway", "mirror")
    val content = com.maodouchat.util.GroupPlayPolicy.formatImpostor(words.random(), label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiStory() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiStory(
        com.maodouchat.util.GroupPlayPolicy.randomEmojiStorySeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSimon() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSimon(
        com.maodouchat.util.GroupPlayPolicy.randomSimonSequence(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendHotOrNot() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val topics = listOf("pineapple on pizza", "night owl life", "remote work", "early meetings", "voice notes")
    val content = com.maodouchat.util.GroupPlayPolicy.formatHotOrNot(topics.random(), label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendAlphabetRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatAlphabet(
        com.maodouchat.util.GroupPlayPolicy.randomAlphabetLetter(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

/** Refresh sealed-sender certificate while secret chat is active (idle keep-alive). */

internal fun ChatDetailViewModel.sendSilentMovie() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSilentMovie(
        com.maodouchat.util.GroupPlayPolicy.randomSilentMovie(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendColorWord() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatColorWord(
        com.maodouchat.util.GroupPlayPolicy.randomColorWord(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendDebateFlash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDebateFlash(
        com.maodouchat.util.GroupPlayPolicy.randomDebateFlash(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendQuickPollPlay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatQuickPoll(
        com.maodouchat.util.GroupPlayPolicy.randomQuickPoll(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMirrorEcho() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMirrorEcho(
        com.maodouchat.util.GroupPlayPolicy.randomMirrorEcho(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSyncClap() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSyncClap(
        com.maodouchat.util.GroupPlayPolicy.randomSyncClap(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendFactOrFiction() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFactOrFiction(
        com.maodouchat.util.GroupPlayPolicy.randomFactOrFiction(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendImpulseDraw() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatImpulseDraw(
        com.maodouchat.util.GroupPlayPolicy.randomImpulseDraw(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWordScramble() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWordScramble(
        com.maodouchat.util.GroupPlayPolicy.randomWordScramble(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendReactionDuel() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatReactionDuel(
        com.maodouchat.util.GroupPlayPolicy.randomReactionDuel(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCodeBreaker() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCodeBreaker(
        com.maodouchat.util.GroupPlayPolicy.randomCodeBreaker(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSillyLaw() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSillyLaw(
        com.maodouchat.util.GroupPlayPolicy.randomSillyLaw(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiMath() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiMath(
        com.maodouchat.util.GroupPlayPolicy.randomEmojiMath(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendPinTheMood() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPinTheMood(
        com.maodouchat.util.GroupPlayPolicy.randomPinTheMood(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRevokeRush() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRevokeRush(
        com.maodouchat.util.GroupPlayPolicy.randomRevokeRush(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSecretSignal() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSecretSignal(
        com.maodouchat.util.GroupPlayPolicy.randomSecretSignal(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMoodMeter() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMoodMeter(
        com.maodouchat.util.GroupPlayPolicy.randomMoodMeter(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendFocusSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFocusSprint(
        com.maodouchat.util.GroupPlayPolicy.randomFocusSprint(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendGratitudeRound() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatGratitudeRound(
        com.maodouchat.util.GroupPlayPolicy.randomGratitudeRound(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendIdeaRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatIdeaRelay(
        com.maodouchat.util.GroupPlayPolicy.randomIdeaRelay(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTempoTap() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTempoTap(
        com.maodouchat.util.GroupPlayPolicy.randomTempoTap(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTranslateRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTranslateRelay(
        com.maodouchat.util.GroupPlayPolicy.randomTranslateRelay(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendInviteRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatInviteRace(
        com.maodouchat.util.GroupPlayPolicy.randomInviteRace(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMentionMayhem() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMentionMayhem(
        com.maodouchat.util.GroupPlayPolicy.randomMentionMayhem(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendLinkHunt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatLinkHunt(
        com.maodouchat.util.GroupPlayPolicy.randomLinkHunt(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendNudgeDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatNudgeDash(
        com.maodouchat.util.GroupPlayPolicy.randomNudgeDash(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCodeCheck() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCodeCheck(
        com.maodouchat.util.GroupPlayPolicy.randomCodeCheck(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTrustSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTrustSprint(
        com.maodouchat.util.GroupPlayPolicy.randomTrustSprint(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendQrQuest() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatQrQuest(
        com.maodouchat.util.GroupPlayPolicy.randomQrQuest(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendContactSwap() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatContactSwap(
        com.maodouchat.util.GroupPlayPolicy.randomContactSwap(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendScanSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatScanSprint(
        com.maodouchat.util.GroupPlayPolicy.randomScanSprint(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSpoilerRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSpoilerRace(
        com.maodouchat.util.GroupPlayPolicy.randomSpoilerRace(), label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}
internal fun ChatDetailViewModel.sendBlurBattle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBlurBattle(
        com.maodouchat.util.GroupPlayPolicy.randomBlurBattle(), label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}
internal fun ChatDetailViewModel.sendDownloadDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDownloadDash(
        com.maodouchat.util.GroupPlayPolicy.randomDownloadDash(), label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendPinDrop() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPinDrop(com.maodouchat.util.GroupPlayPolicy.randomPinDrop(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFileRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFileRelay(com.maodouchat.util.GroupPlayPolicy.randomFileRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendMapDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMapDash(com.maodouchat.util.GroupPlayPolicy.randomMapDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendVaultLock() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatVaultLock(com.maodouchat.util.GroupPlayPolicy.randomVaultLock(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendWatermarkHunt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWatermarkHunt(com.maodouchat.util.GroupPlayPolicy.randomWatermarkHunt(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSecureSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSecureSprint(com.maodouchat.util.GroupPlayPolicy.randomSecureSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}

internal fun ChatDetailViewModel.createQuickPoll(question: String, options: List<String>) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.POLLS)) {
        _uiState.update { it.copy(errorMessage = text(R.string.group_play_poll_disabled)) }
        return
    }
    if (!requireGroupPlay()) return
    viewModelScope.launch {
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return@launch
        val chatId = activeChatId
        if (chatId.isBlank()) return@launch
        ApiService.createGroupPoll(token, chatId, question, options).fold(
            onSuccess = {
                _uiState.update { st -> st.copy(infoMessage = text(R.string.group_play_vote_ok)) }
            },
            onFailure = {
                _uiState.update { st -> st.copy(errorMessage = text(R.string.group_play_poll_failed)) }
            }
        )
    }
}

internal fun ChatDetailViewModel.votePoll(pollId: String, optionIndex: Int) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.POLLS)) {
        _uiState.update { it.copy(errorMessage = text(R.string.group_play_poll_disabled)) }
        return
    }
    if (!requireGroupPlay()) return
    if (pollId.isBlank()) return
    viewModelScope.launch {
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return@launch
        ApiService.voteGroupPoll(token, pollId, listOf(optionIndex)).fold(
            onSuccess = {
                _uiState.update { st -> st.copy(infoMessage = text(R.string.group_play_vote_ok)) }
            },
            onFailure = {
                _uiState.update { st -> st.copy(errorMessage = text(R.string.group_play_poll_failed)) }
            }
        )
    }
}

internal fun ChatDetailViewModel.sendPhotoRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPhotoRace(com.maodouchat.util.GroupPlayPolicy.randomPhotoRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendClipDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatClipDash(com.maodouchat.util.GroupPlayPolicy.randomClipDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFrameHunt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFrameHunt(com.maodouchat.util.GroupPlayPolicy.randomFrameHunt(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSummaryCircle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSummaryCircle(com.maodouchat.util.GroupPlayPolicy.randomSummaryCircle(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendRewriteRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRewriteRelay(com.maodouchat.util.GroupPlayPolicy.randomRewriteRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPromptSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPromptSprint(com.maodouchat.util.GroupPlayPolicy.randomPromptSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSuggestCircle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSuggestCircle(com.maodouchat.util.GroupPlayPolicy.randomSuggestCircle(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendVoiceRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatVoiceRace(com.maodouchat.util.GroupPlayPolicy.randomVoiceRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendReplySprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatReplySprint(com.maodouchat.util.GroupPlayPolicy.randomReplySprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPixelQuest() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPixelQuest(com.maodouchat.util.GroupPlayPolicy.randomPixelQuest(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendAssistCircle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatAssistCircle(com.maodouchat.util.GroupPlayPolicy.randomAssistCircle(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendDecisionDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDecisionDash(com.maodouchat.util.GroupPlayPolicy.randomDecisionDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendDocHunt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDocHunt(com.maodouchat.util.GroupPlayPolicy.randomDocHunt(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendMeaningRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMeaningRace(com.maodouchat.util.GroupPlayPolicy.randomMeaningRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendInsightSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatInsightSprint(com.maodouchat.util.GroupPlayPolicy.randomInsightSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendGifRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatGifRelay(com.maodouchat.util.GroupPlayPolicy.randomGifRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendMarkHunt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMarkHunt(com.maodouchat.util.GroupPlayPolicy.randomMarkHunt(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendLeakSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatLeakSprint(com.maodouchat.util.GroupPlayPolicy.randomLeakSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendVoiceRing() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatVoiceRing(com.maodouchat.util.GroupPlayPolicy.randomVoiceRing(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendVideoStage() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatVideoStage(com.maodouchat.util.GroupPlayPolicy.randomVideoStage(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendRingDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRingDash(com.maodouchat.util.GroupPlayPolicy.randomRingDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendWallPick() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWallPick(com.maodouchat.util.GroupPlayPolicy.randomWallPick(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFontRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFontRace(com.maodouchat.util.GroupPlayPolicy.randomFontRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendThemeSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatThemeSprint(com.maodouchat.util.GroupPlayPolicy.randomThemeSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendUnreadRush() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatUnreadRush(com.maodouchat.util.GroupPlayPolicy.randomUnreadRush(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendRingChoir() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRingChoir(com.maodouchat.util.GroupPlayPolicy.randomRingChoir(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendAlertSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatAlertSprint(com.maodouchat.util.GroupPlayPolicy.randomAlertSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSoundWave() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSoundWave(com.maodouchat.util.GroupPlayPolicy.randomSoundWave(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPreviewMask() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPreviewMask(com.maodouchat.util.GroupPlayPolicy.randomPreviewMask(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendBeepDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBeepDash(com.maodouchat.util.GroupPlayPolicy.randomBeepDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPushRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPushRace(com.maodouchat.util.GroupPlayPolicy.randomPushRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendRemindCircle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRemindCircle(com.maodouchat.util.GroupPlayPolicy.randomRemindCircle(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendWakeSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWakeSprint(com.maodouchat.util.GroupPlayPolicy.randomWakeSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendQuietHour() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatQuietHour(com.maodouchat.util.GroupPlayPolicy.randomQuietHour(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendOfflineHint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatOfflineHint(com.maodouchat.util.GroupPlayPolicy.randomOfflineHint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFallbackDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFallbackDash(com.maodouchat.util.GroupPlayPolicy.randomFallbackDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendClickBeat() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatClickBeat(com.maodouchat.util.GroupPlayPolicy.randomClickBeat(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendBuzzRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBuzzRelay(com.maodouchat.util.GroupPlayPolicy.randomBuzzRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFeelSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFeelSprint(com.maodouchat.util.GroupPlayPolicy.randomFeelSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSlideRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSlideRace(com.maodouchat.util.GroupPlayPolicy.randomSlideRace(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFadeCircle() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFadeCircle(com.maodouchat.util.GroupPlayPolicy.randomFadeCircle(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSpringDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSpringDash(com.maodouchat.util.GroupPlayPolicy.randomSpringDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSnapGuard() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSnapGuard(com.maodouchat.util.GroupPlayPolicy.randomSnapGuard(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendRecentsHide() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRecentsHide(com.maodouchat.util.GroupPlayPolicy.randomRecentsHide(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendShieldSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatShieldSprint(com.maodouchat.util.GroupPlayPolicy.randomShieldSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendCopyLock() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCopyLock(com.maodouchat.util.GroupPlayPolicy.randomCopyLock(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendExportSeal() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatExportSeal(com.maodouchat.util.GroupPlayPolicy.randomExportSeal(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendLeakWall() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatLeakWall(com.maodouchat.util.GroupPlayPolicy.randomLeakWall(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendForwardSeal() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatForwardSeal(com.maodouchat.util.GroupPlayPolicy.randomForwardSeal(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendChatExportLock() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatChatExportLock(com.maodouchat.util.GroupPlayPolicy.randomChatExportLock(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendVaultFence() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatVaultFence(com.maodouchat.util.GroupPlayPolicy.randomVaultFence(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendSealSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSealSprint(com.maodouchat.util.GroupPlayPolicy.randomSealSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPqxdhDash() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPqxdhDash(com.maodouchat.util.GroupPlayPolicy.randomPqxdhDash(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendCertRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCertRelay(com.maodouchat.util.GroupPlayPolicy.randomCertRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendMarkSprint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMarkSprint(com.maodouchat.util.GroupPlayPolicy.randomMarkSprint(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendFadeTimer() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFadeTimer(com.maodouchat.util.GroupPlayPolicy.randomFadeTimer(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendStampRelay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatStampRelay(com.maodouchat.util.GroupPlayPolicy.randomStampRelay(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendLinkLock() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatLinkLock(com.maodouchat.util.GroupPlayPolicy.randomLinkLock(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendPreviewMute() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPreviewMute(com.maodouchat.util.GroupPlayPolicy.randomPreviewMute(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendUrlFence() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatUrlFence(com.maodouchat.util.GroupPlayPolicy.randomUrlFence(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendNotifMask() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatNotifMask(com.maodouchat.util.GroupPlayPolicy.randomNotifMask(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendListBlur() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatListBlur(com.maodouchat.util.GroupPlayPolicy.randomListBlur(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendTraySeal() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTraySeal(com.maodouchat.util.GroupPlayPolicy.randomTraySeal(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}

internal fun ChatDetailViewModel.sendReactLock() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatReactLock(com.maodouchat.util.GroupPlayPolicy.randomReactLock(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendStarSeal() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatStarSeal(com.maodouchat.util.GroupPlayPolicy.randomStarSeal(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}
internal fun ChatDetailViewModel.sendMetaFence() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMetaFence(com.maodouchat.util.GroupPlayPolicy.randomMetaFence(), label)
    _uiState.update { it.copy(inputText = content) }; sendMessage()
}

internal fun ChatDetailViewModel.sendTrivia() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (q, a) = com.maodouchat.util.GroupPlayPolicy.randomTrivia()
    val content = com.maodouchat.util.GroupPlayPolicy.formatTrivia(q, a, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSpeedChallenge() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSpeedChallenge(15, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTruthOrDare(preferDare: Boolean = false) {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val mode = if (preferDare) "dare" else listOf("truth", "dare").random()
    val prompt = if (mode == "dare") {
        com.maodouchat.util.GroupPlayPolicy.randomDare()
    } else {
        com.maodouchat.util.GroupPlayPolicy.randomTruthPrompt()
    }
    val content = com.maodouchat.util.GroupPlayPolicy.formatTruthOrDare(mode, prompt, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendNeverHaveIEver() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatNeverHaveIEver(
        com.maodouchat.util.GroupPlayPolicy.randomNeverHave(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMemoryMatch() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMemoryMatch(
        com.maodouchat.util.GroupPlayPolicy.randomMemoryBoard(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendDrawPrompt() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDrawPrompt(
        com.maodouchat.util.GroupPlayPolicy.randomDrawPrompt(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendIcebreaker() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatIcebreaker(
        com.maodouchat.util.GroupPlayPolicy.randomIcebreaker(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiDuel() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiDuel(
        com.maodouchat.util.GroupPlayPolicy.randomEmojiDuel(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRapidFire() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRapidFire(
        com.maodouchat.util.GroupPlayPolicy.randomRapidTopic(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendScattergories() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (letter, cat) = com.maodouchat.util.GroupPlayPolicy.randomScatter()
    val content = com.maodouchat.util.GroupPlayPolicy.formatScatter(letter, cat, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMinuteTalk() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMinuteTalk(
        com.maodouchat.util.GroupPlayPolicy.randomTalkTopic(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCaptionThis() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCaptionThis(
        com.maodouchat.util.GroupPlayPolicy.randomCaptionSeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendStorySwap() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatStorySwap(
        com.maodouchat.util.GroupPlayPolicy.randomStoryOpener(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendKaraokeChallenge() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatKaraoke(
        com.maodouchat.util.GroupPlayPolicy.randomKaraoke(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendBlindQ() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBlindQ(
        com.maodouchat.util.GroupPlayPolicy.randomBlindQ(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.requireVoiceMessages(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.VOICE_MESSAGES)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.voice_messages_disabled)) }
        return false
    }
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.MEDIA_UPLOAD)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_media_upload_disabled)) }
        return false
    }
    return true
}

internal fun ChatDetailViewModel.requireStickers(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.STICKERS)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.stickers_disabled)) }
        return false
    }
    return true
}

internal fun ChatDetailViewModel.requireSilentSend(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.SILENT_SEND)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.silent_send_disabled)) }
        return false
    }
    return true
}

internal fun ChatDetailViewModel.requireReactions(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.REACTIONS)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.reactions_disabled)) }
        return false
    }
    if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(ctx, RuntimeFlags.SECRET_REACTION_BLOCK)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_reaction_blocked)) }
        return false
    }
    return true
}

internal fun ChatDetailViewModel.sendFortuneCookie() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatFortune(
        com.maodouchat.util.GroupPlayPolicy.randomFortune(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiQuiz() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (p, a) = com.maodouchat.util.GroupPlayPolicy.randomEmojiQuiz()
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiQuiz(p, a, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendChainReact() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatChainReact(
        com.maodouchat.util.GroupPlayPolicy.randomChainSeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendDebateTopic() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatDebate(
        com.maodouchat.util.GroupPlayPolicy.randomDebateTopic(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendMirrorLine() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatMirror(
        com.maodouchat.util.GroupPlayPolicy.randomMirrorLine(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendHideSeek() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatHideSeek(
        com.maodouchat.util.GroupPlayPolicy.randomHideEmoji(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendFriendlyToast() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatToast(
        com.maodouchat.util.GroupPlayPolicy.randomToast(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendHotPotato() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatHotPotato(
        com.maodouchat.util.GroupPlayPolicy.randomHotPotatoSeconds(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWordHint() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (h, a) = com.maodouchat.util.GroupPlayPolicy.randomWordHint()
    val content = com.maodouchat.util.GroupPlayPolicy.formatWordHint(h, a, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSpyfall() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSpyfall(
        com.maodouchat.util.GroupPlayPolicy.randomSpyLocation(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendAcrostic() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatAcrostic(
        com.maodouchat.util.GroupPlayPolicy.randomAcrostic(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiTranslate() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (p, a) = com.maodouchat.util.GroupPlayPolicy.randomEmojiTr()
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiTranslate(p, a, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTwentyQuestions() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTwentyQuestions(
        com.maodouchat.util.GroupPlayPolicy.randomTwentySubject(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRhymeChain() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRhyme(
        com.maodouchat.util.GroupPlayPolicy.randomRhymeSeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendOddOneOut() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (opts, ans) = com.maodouchat.util.GroupPlayPolicy.randomOddOne()
    val content = com.maodouchat.util.GroupPlayPolicy.formatOddOneOut(opts, ans, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCategoriesPlay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCategories(
        com.maodouchat.util.GroupPlayPolicy.randomCategory(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendPasswordGame() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatPasswordGame(
        com.maodouchat.util.GroupPlayPolicy.randomPasswordHint(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTimeCapsule() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTimeCapsule(
        com.maodouchat.util.GroupPlayPolicy.randomCapsule(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTaboo() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTaboo(
        com.maodouchat.util.GroupPlayPolicy.randomTaboo(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendLightningRound() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatLightning(
        com.maodouchat.util.GroupPlayPolicy.randomLightning(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendTwoWordStory() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatTwoWords(
        com.maodouchat.util.GroupPlayPolicy.randomTwoWords(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWhisperChallenge() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatWhisper(
        com.maodouchat.util.GroupPlayPolicy.randomWhisper(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendCountdownRace() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatCountdownRace(
        com.maodouchat.util.GroupPlayPolicy.randomCountdownRace(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendRapidFirePlay() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatRapidFire(
        com.maodouchat.util.GroupPlayPolicy.randomRapidTopic(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiMemory() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiMemory(
        com.maodouchat.util.GroupPlayPolicy.randomEmojiMemory(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendGeoGuess() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatGeoGuess(
        com.maodouchat.util.GroupPlayPolicy.randomGeoClue(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendOneWordStory() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatOneWord(
        com.maodouchat.util.GroupPlayPolicy.randomOneWord(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendSpeedMath() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatSpeedMath(
        com.maodouchat.util.GroupPlayPolicy.randomMathQ(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendStorySeed() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatStorySeed(
        com.maodouchat.util.GroupPlayPolicy.randomStorySeed(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendWouldYouRather2() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val (a, b) = com.maodouchat.util.GroupPlayPolicy.randomWould2()
    val content = com.maodouchat.util.GroupPlayPolicy.formatWould2(a, b, label)
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendEmojiOnlyChallenge() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatEmojiOnly(
        com.maodouchat.util.GroupPlayPolicy.randomEmojiOnly(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}

internal fun ChatDetailViewModel.sendBlindDraw() {
    if (!requireGroupPlay()) return
    val label = tokenManager.getUserId()?.take(8) ?: "me"
    val content = com.maodouchat.util.GroupPlayPolicy.formatBlindDraw(
        com.maodouchat.util.GroupPlayPolicy.randomBlindDraw(),
        label
    )
    _uiState.update { it.copy(inputText = content) }
    sendMessage()
}
