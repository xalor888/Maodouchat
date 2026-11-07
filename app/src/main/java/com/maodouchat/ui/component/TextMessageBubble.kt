package com.maodouchat.ui.component

import com.maodouchat.util.RuntimeFlags
import com.maodouchat.core.model.CaptureAlertPolicy
import com.maodouchat.core.model.GroupPlayPolicy
import com.maodouchat.core.model.ReplyPreview
import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.navigation.AppLinkOpener
import com.maodouchat.core.model.ChatMarkdown

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun TextBubble(
    message: Message,
    presentation: MessagePresentation,
    isOwnMessage: Boolean,
    modifier: Modifier,
    showAvatar: Boolean,
    showSenderName: Boolean,
    isGroupEdge: Boolean,
    senderName: String?,
    mentionedUserIds: List<String> = emptyList(),
    replyToPreview: ReplyPreview? = null,
    onReply: ((Message) -> Unit)? = null,
    onReplyPreviewClick: ((Message) -> Unit)? = null,
    onBoundsMeasured: ((IntOffset, IntSize) -> Unit)? = null,
    translationText: String? = null,
    isTranslating: Boolean = false,
    isAiAssisted: Boolean = false,
    currentUserId: String? = null,
    safetyWarning: String? = null,
    onDismissSafety: (() -> Unit)? = null,
    onReactionClick: ((String) -> Unit)? = null,
    onPollVote: ((String, Int) -> Unit)? = null,
    secretChatId: String? = null,
    onInlineKeyboardClick: ((String, String) -> Unit)? = null,
    /** 1.17：点击消息内联系人名片 → 打开该用户资料。 */
    onContactCardClick: ((String) -> Unit)? = null,
    /** 1.44：点击消息发送者名称 → 打开其资料。 */
    onSenderClick: ((String) -> Unit)? = null,
    /** 9.1xx：点击已读状态图标（✓✓）→ 打开阅读详情。 */
    onStatusClick: ((Message) -> Unit)? = null,
    /** 0.65：发送者群内角色（群主/管理员徽章，仅群聊显示）。 */
    memberRole: String? = null,
    showStatusIcon: Boolean = true
) {
    val displayBody = if (presentation.requiresDecryptPlaceholder) {
        stringResource(R.string.chat_decrypt_pending)
    } else {
        presentation.body
    }
    val palette = LocalChatPalette.current
    // 9.252：TG 式动态气泡宽度——此前固定 280dp，大屏上气泡偏窄、长文本折行过多
    // 观感拥挤；参考 TG ChatMessageCell 按屏宽比例（平板封顶 480dp）
    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).coerceAtMost(480f).dp
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        if (!isOwnMessage && senderName != null && showSenderName) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 44.dp, bottom = 2.dp)
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textHint,
                    modifier = Modifier.clickable(enabled = onSenderClick != null) {
                        onSenderClick?.invoke(message.senderId)
                    }
                )
                when (memberRole) {
                    "OWNER" -> RoleBadge(stringResource(R.string.chat_role_owner), owner = true)
                    "ADMIN" -> RoleBadge(stringResource(R.string.chat_role_admin), owner = false)
                    else -> Unit
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isOwnMessage) {
                if (showAvatar) {
                    Avatar(
                        name = senderName ?: "?",
                        size = AvatarSize.SM,
                    )
                } else {
                    Spacer(modifier = Modifier.width(36.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(
                horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = bubbleMaxWidth)
            ) {
            // 0.67 新功能：已转发标记（E2EE meta 内传输，密聊转发仅标记不露来源名）
            val forwardedFrom = presentation.meta.forwardedFrom
            if (forwardedFrom != null) {
                Text(
                    text = stringResource(R.string.message_forwarded_from, forwardedFrom),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }

            if (replyToPreview != null) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.6f) else palette.chatInputBackground)
                        .then(if (onReplyPreviewClick != null) Modifier.clickable { onReplyPreviewClick(message) } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.message_reply_preview, replyToPreview.senderName, replyToPreview.preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint,
                        maxLines = 1
                    )
                }
            }

            // 9.267：TG 式角尾——showAvatar 即组尾（含单条消息组），尾角侧用小圆角
            val bubbleShape = resolveBubbleShape(isOwnMessage, isGroupEdge = isGroupEdge)
            Column(
                modifier = Modifier
                    .captureBubbleBounds(onBoundsMeasured)
                    .clip(bubbleShape)
                    .background(
                        if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.9f)
                        else palette.chatBubbleReceived
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                if (isAiAssisted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (isOwnMessage) LocalSentBubbleContentSecondary.current else Primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.message_ai_assisted_shared),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else Primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                val parsedMeta = presentation.meta
                val dice = GroupPlayPolicy.parseDice(displayBody)
                if (dice != null) {
                    val (sides, value) = dice
                    Text(
                        text = "🎲 $value / $sides",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                    )
                    return@Column
                }
                val lucky = GroupPlayPolicy.parseLuckyDraw(displayBody)
                if (lucky != null) {
                    val (picker, target) = lucky
                    Text(
                        text = "🎉 $picker → $target",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                    )
                    return@Column
                }
                val parsedBody = displayBody
                if (CaptureAlertPolicy.isCaptureAlert(parsedBody)) {
                    // 8.49 防御：解析失败直接跳过（此前 parse()!! 依赖「isCaptureAlert 与 parse 永远一致」的脆弱不变量）
                    val parsedAlert = CaptureAlertPolicy.parse(parsedBody) ?: return@Column
                    val (_, detail) = parsedAlert
                    Text(
                        text = "ALERT: $detail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else Error,
                        fontWeight = FontWeight.SemiBold
                    )
                    return@Column
                }
                val poll = GroupPlayPolicy.parsePoll(parsedBody)
                if (poll != null) {
                    InteractivePollCard(
                        pollJson = poll,
                        isOwnMessage = isOwnMessage,
                        onVote = onPollVote
                    )
                    return@Column
                }
                val playLabel = run {
                    GroupPlayPolicy.parseDice(parsedBody)?.let { (sides, value) -> return@run "Dice $value / $sides" }
                    GroupPlayPolicy.parseRps(parsedBody)?.let { return@run "RPS: ${it}" }
                    GroupPlayPolicy.parseLuckyDraw(parsedBody)?.let { (picker, target) -> return@run "Lucky: $picker -> $target" }
                    if (parsedBody.startsWith(GroupPlayPolicy.CHECKIN_PREFIX)) { return@run parsedBody.removePrefix(GroupPlayPolicy.CHECKIN_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(GroupPlayPolicy.TRUTH_PREFIX)) { return@run "Truth: " + parsedBody.removePrefix(GroupPlayPolicy.TRUTH_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(GroupPlayPolicy.ANON_PREFIX)) { return@run "Anon: " + parsedBody.removePrefix(GroupPlayPolicy.ANON_PREFIX).substringAfter('|', parsedBody) }
                    GroupPlayPolicy.parseReactionRace(parsedBody)?.let { (token, label) -> return@run "Race $token: $label" }
                    GroupPlayPolicy.parseNumberBomb(parsedBody)?.let { (max, _, label) -> return@run "Bomb 1-$max: $label" }
                    if (parsedBody.startsWith(GroupPlayPolicy.WORD_PREFIX)) { return@run parsedBody.removePrefix(GroupPlayPolicy.WORD_PREFIX).substringAfter('|', parsedBody) }
                    GroupPlayPolicy.parseWouldYouRather(parsedBody)?.let { (a, b, _) -> return@run "Would you rather: $a  OR  $b" }
                    if (parsedBody.startsWith(GroupPlayPolicy.EMOJI_RAIN_PREFIX)) { return@run parsedBody.removePrefix(GroupPlayPolicy.EMOJI_RAIN_PREFIX).substringAfter('|', parsedBody) }
                    GroupPlayPolicy.parseTwoTruthsOneLie(parsedBody)?.let { items -> return@run "Two truths & one lie: " + items.joinToString(" / ") }
                    if (parsedBody.startsWith(GroupPlayPolicy.QUIZ_PREFIX)) {
                        // 9.224：走 parseQuiz 拿到 unesc 后的题目（直接 substringBefore 会残留转义符）
                        val q = GroupPlayPolicy.parseQuiz(parsedBody)?.first
                            ?: parsedBody.removePrefix(GroupPlayPolicy.QUIZ_PREFIX).substringBefore('|')
                        return@run "Quiz: $q"
                    }
                    GroupPlayPolicy.parseCharades(parsedBody)?.let { return@run "Charades: ${it}" }
                    GroupPlayPolicy.parseNumberGuess(parsedBody)?.let { (_, max) -> return@run "Number guess 1..$max" }
                    GroupPlayPolicy.parseRiddle(parsedBody)?.let { (q, _) -> return@run "Riddle: $q" }
                    GroupPlayPolicy.parseImpostor(parsedBody)?.let { return@run "Impostor game started" }
                    GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    GroupPlayPolicy.parseSimon(parsedBody)?.let { return@run "Simon: ${it}" }
                    GroupPlayPolicy.parseHotOrNot(parsedBody)?.let { return@run "Hot or not: ${it}" }
                    GroupPlayPolicy.parseAlphabet(parsedBody)?.let { return@run "Alphabet ${it}" }
                    GroupPlayPolicy.parseTrivia(parsedBody)?.let { (q, _) -> return@run "Trivia: $q" }
                    GroupPlayPolicy.parseSpeedChallenge(parsedBody)?.let { return@run "Speed ${it}s" }
                    GroupPlayPolicy.parseFortune(parsedBody)?.let { return@run "Fortune: ${it}" }
                    GroupPlayPolicy.parseEmojiQuiz(parsedBody)?.let { (p, _) -> return@run "Emoji quiz: $p" }
                    GroupPlayPolicy.parseChainReact(parsedBody)?.let { return@run "Chain: ${it}" }
                    GroupPlayPolicy.parseDebate(parsedBody)?.let { return@run "Debate: ${it}" }
                    GroupPlayPolicy.parseMirror(parsedBody)?.let { return@run "Mirror: ${it}" }
                    GroupPlayPolicy.parseHideSeek(parsedBody)?.let { return@run "Hide&Seek: ${it}" }
                    GroupPlayPolicy.parseToast(parsedBody)?.let { return@run "Roast: ${it}" }
                    GroupPlayPolicy.parseHotPotato(parsedBody)?.let { return@run "Hot potato ${it}s" }
                    GroupPlayPolicy.parseWordHint(parsedBody)?.let { (h, _) -> return@run "Word hint: $h" }
                    GroupPlayPolicy.parseSpyfall(parsedBody)?.let { return@run "Spyfall @ ${it}" }
                    GroupPlayPolicy.parseAcrostic(parsedBody)?.let { return@run "Acrostic: ${it}" }
                    GroupPlayPolicy.parseEmojiTranslate(parsedBody)?.let { (p, _) -> return@run "Emoji TR: $p" }
                    GroupPlayPolicy.parseTwentyQuestions(parsedBody)?.let { return@run "20Q: ${it}" }
                    GroupPlayPolicy.parseRhyme(parsedBody)?.let { return@run "Rhyme: ${it}" }
                    GroupPlayPolicy.parseOddOneOut(parsedBody)?.let { (o, _) -> return@run "Odd one: $o" }
                    GroupPlayPolicy.parseCategories(parsedBody)?.let { return@run "Categories: ${it}" }
                    GroupPlayPolicy.parsePasswordGame(parsedBody)?.let { return@run "Password game: ${it}" }
                    GroupPlayPolicy.parseTimeCapsule(parsedBody)?.let { return@run "Capsule: ${it}" }
                    GroupPlayPolicy.parseTaboo(parsedBody)?.let { return@run "Taboo: ${it.substringBefore('|')}" }
                    GroupPlayPolicy.parseLightning(parsedBody)?.let { return@run "Lightning: ${it}" }
                    GroupPlayPolicy.parseTwoWords(parsedBody)?.let { return@run "Two words: ${it}" }
                    GroupPlayPolicy.parseWhisper(parsedBody)?.let { return@run "Whisper: ${it}" }
                    GroupPlayPolicy.parseEmojiDuel(parsedBody)?.let { return@run "Emoji duel: ${it}" }
                    GroupPlayPolicy.parseCountdownRace(parsedBody)?.let { return@run "Race ${it}s" }
                    GroupPlayPolicy.parseRapidFire(parsedBody)?.let { return@run "Rapid: ${it}" }
                    GroupPlayPolicy.parseEmojiMemory(parsedBody)?.let { return@run "Memory: ${it}" }
                    GroupPlayPolicy.parseGeoGuess(parsedBody)?.let { return@run "Geo: ${it}" }
                    GroupPlayPolicy.parseOneWord(parsedBody)?.let { return@run "One word: ${it}" }
                    GroupPlayPolicy.parseSpeedMath(parsedBody)?.let { return@run "Math: ${it}" }
                    GroupPlayPolicy.parseStorySeed(parsedBody)?.let { return@run "Story: ${it}" }
                    GroupPlayPolicy.parseWould2(parsedBody)?.let { (a, b) -> return@run "Would you: $a OR $b" }
                    GroupPlayPolicy.parseEmojiOnly(parsedBody)?.let { return@run "Emoji only: ${it}" }
                    GroupPlayPolicy.parseBlindDraw(parsedBody)?.let { return@run "Blind draw" }
                    GroupPlayPolicy.parseAlphabetRace(parsedBody)?.let { return@run "Alphabet: ${it}" }
                    GroupPlayPolicy.parseSilentMovie(parsedBody)?.let { return@run "Silent movie: ${it}" }
                    GroupPlayPolicy.parseColorWord(parsedBody)?.let { return@run "Color word: ${it}" }
                    GroupPlayPolicy.parseDebateFlash(parsedBody)?.let { return@run "Debate: ${it}" }
                    GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    GroupPlayPolicy.parseQuickPoll(parsedBody)?.let { return@run "Quick poll: ${it}" }
                    GroupPlayPolicy.parseMirrorEcho(parsedBody)?.let { return@run "Mirror: ${it}" }
                    GroupPlayPolicy.parseSyncClap(parsedBody)?.let { return@run "Sync clap x${it}" }
                    GroupPlayPolicy.parseFactOrFiction(parsedBody)?.let { return@run "Fact?: ${it}" }
                    GroupPlayPolicy.parseImpulseDraw(parsedBody)?.let { return@run "Impulse: ${it}" }
                    GroupPlayPolicy.parseWordScramble(parsedBody)?.let { return@run "Scramble: ${it}" }
                    GroupPlayPolicy.parseReactionDuel(parsedBody)?.let { return@run "React duel: ${it}" }
                    GroupPlayPolicy.parseCodeBreaker(parsedBody)?.let { return@run "Code breaker" }
                    GroupPlayPolicy.parseSillyLaw(parsedBody)?.let { return@run "Law: ${it}" }
                    GroupPlayPolicy.parseEmojiMath(parsedBody)?.let { return@run "Emoji math: ${it}" }
                    GroupPlayPolicy.parsePinTheMood(parsedBody)?.let { return@run "Mood pin: ${it}" }
                    GroupPlayPolicy.parseRevokeRush(parsedBody)?.let { return@run "Revoke rush ${it}" }
                    GroupPlayPolicy.parseSecretSignal(parsedBody)?.let { return@run "Signal: ${it}" }
                    GroupPlayPolicy.parseMoodMeter(parsedBody)?.let { return@run "Mood meter: ${it}" }
                    GroupPlayPolicy.parseFocusSprint(parsedBody)?.let { return@run "Focus sprint ${it}" }
                    GroupPlayPolicy.parseGratitudeRound(parsedBody)?.let { return@run "Gratitude: ${it}" }
                    GroupPlayPolicy.parseIdeaRelay(parsedBody)?.let { return@run "Idea relay: ${it}" }
                    GroupPlayPolicy.parseTempoTap(parsedBody)?.let { return@run "Tempo tap ${it}" }
                    GroupPlayPolicy.parseTranslateRelay(parsedBody)?.let { return@run "Translate: ${it}" }
                    GroupPlayPolicy.parseInviteRace(parsedBody)?.let { return@run "Invite race: ${it}" }
                    GroupPlayPolicy.parseMentionMayhem(parsedBody)?.let { return@run "Mention mayhem: ${it}" }
                    GroupPlayPolicy.parseLinkHunt(parsedBody)?.let { return@run "Link hunt: ${it}" }
                    GroupPlayPolicy.parseNudgeDash(parsedBody)?.let { return@run "Nudge dash: ${it}" }
                    GroupPlayPolicy.parseCodeCheck(parsedBody)?.let { return@run "Code check: ${it}" }
                    GroupPlayPolicy.parseTrustSprint(parsedBody)?.let { return@run "Trust sprint: ${it}" }
                    GroupPlayPolicy.parseQrQuest(parsedBody)?.let { return@run "QR quest: ${it}" }
                    GroupPlayPolicy.parseContactSwap(parsedBody)?.let { return@run "Contact swap: ${it}" }
                    GroupPlayPolicy.parseScanSprint(parsedBody)?.let { return@run "Scan sprint: ${it}" }
                    GroupPlayPolicy.parseSpoilerRace(parsedBody)?.let { return@run "Spoiler race: ${it}" }
                    GroupPlayPolicy.parseBlurBattle(parsedBody)?.let { return@run "Blur battle: ${it}" }
                    GroupPlayPolicy.parseDownloadDash(parsedBody)?.let { return@run "Download dash: ${it}" }
                    GroupPlayPolicy.parsePinDrop(parsedBody)?.let { return@run "Pin drop: ${it}" }
                    GroupPlayPolicy.parseFileRelay(parsedBody)?.let { return@run "File relay: ${it}" }
                    GroupPlayPolicy.parseMapDash(parsedBody)?.let { return@run "Map dash: ${it}" }
                    GroupPlayPolicy.parseVaultLock(parsedBody)?.let { return@run "Vault lock: ${it}" }
                    GroupPlayPolicy.parseWatermarkHunt(parsedBody)?.let { return@run "Watermark hunt: ${it}" }
                    GroupPlayPolicy.parseSecureSprint(parsedBody)?.let { return@run "Secure sprint: ${it}" }
                    GroupPlayPolicy.parsePhotoRace(parsedBody)?.let { return@run "Photo race: ${it}" }
                    GroupPlayPolicy.parseClipDash(parsedBody)?.let { return@run "Clip dash: ${it}" }
                    GroupPlayPolicy.parseFrameHunt(parsedBody)?.let { return@run "Frame hunt: ${it}" }
                    GroupPlayPolicy.parseSummaryCircle(parsedBody)?.let { return@run "Summary circle: ${it}" }
                    GroupPlayPolicy.parseRewriteRelay(parsedBody)?.let { return@run "Rewrite relay: ${it}" }
                    GroupPlayPolicy.parsePromptSprint(parsedBody)?.let { return@run "Prompt sprint: ${it}"
                    }
                    GroupPlayPolicy.parseSuggestCircle(parsedBody)?.let { return@run "Suggest circle: " + it }
                    GroupPlayPolicy.parseVoiceRace(parsedBody)?.let { return@run "Voice race: " + it }
                    GroupPlayPolicy.parseReplySprint(parsedBody)?.let { return@run "Reply sprint: " + it
                    }
                    GroupPlayPolicy.parsePixelQuest(parsedBody)?.let { return@run "Pixel quest: " + it }
                    GroupPlayPolicy.parseAssistCircle(parsedBody)?.let { return@run "Assist circle: " + it }
                    GroupPlayPolicy.parseDecisionDash(parsedBody)?.let { return@run "Decision dash: " + it
                    }
                    GroupPlayPolicy.parseDocHunt(parsedBody)?.let { return@run "Doc hunt: " + it }
                    GroupPlayPolicy.parseMeaningRace(parsedBody)?.let { return@run "Meaning race: " + it }
                    GroupPlayPolicy.parseInsightSprint(parsedBody)?.let { return@run "Insight sprint: " + it }
                    GroupPlayPolicy.parseGifRelay(parsedBody)?.let { return@run "Gif relay: " + it }
                    GroupPlayPolicy.parseMarkHunt(parsedBody)?.let { return@run "Mark hunt: " + it }
                    GroupPlayPolicy.parseLeakSprint(parsedBody)?.let { return@run "Leak sprint: " + it }
                    GroupPlayPolicy.parseVoiceRing(parsedBody)?.let { return@run "Voice ring: " + it }
                    GroupPlayPolicy.parseVideoStage(parsedBody)?.let { return@run "Video stage: " + it }
                    GroupPlayPolicy.parseRingDash(parsedBody)?.let { return@run "Ring dash: " + it }
                    GroupPlayPolicy.parseWallPick(parsedBody)?.let { return@run "Wall pick: " + it }
                    GroupPlayPolicy.parseFontRace(parsedBody)?.let { return@run "Font race: " + it }
                    GroupPlayPolicy.parseThemeSprint(parsedBody)?.let { return@run "Theme sprint: " + it }
                    GroupPlayPolicy.parseUnreadRush(parsedBody)?.let { return@run "Unread rush: " + it }
                    GroupPlayPolicy.parseRingChoir(parsedBody)?.let { return@run "Ring choir: " + it }
                    GroupPlayPolicy.parseAlertSprint(parsedBody)?.let { return@run "Alert sprint: " + it }
                    GroupPlayPolicy.parseSoundWave(parsedBody)?.let { return@run "Sound wave: " + it }
                    GroupPlayPolicy.parsePreviewMask(parsedBody)?.let { return@run "Preview mask: " + it }
                    GroupPlayPolicy.parseBeepDash(parsedBody)?.let { return@run "Beep dash: " + it }
                    GroupPlayPolicy.parsePushRace(parsedBody)?.let { return@run "Push race: " + it }
                    GroupPlayPolicy.parseRemindCircle(parsedBody)?.let { return@run "Remind circle: " + it }
                    GroupPlayPolicy.parseWakeSprint(parsedBody)?.let { return@run "Wake sprint: " + it }
                    GroupPlayPolicy.parseQuietHour(parsedBody)?.let { return@run "Quiet hour: " + it }
                    GroupPlayPolicy.parseOfflineHint(parsedBody)?.let { return@run "Offline hint: " + it }
                    GroupPlayPolicy.parseFallbackDash(parsedBody)?.let { return@run "Fallback dash: " + it }
                    GroupPlayPolicy.parseClickBeat(parsedBody)?.let { return@run "Click beat: " + it }
                    GroupPlayPolicy.parseBuzzRelay(parsedBody)?.let { return@run "Buzz relay: " + it }
                    GroupPlayPolicy.parseFeelSprint(parsedBody)?.let { return@run "Feel sprint: " + it }
                    GroupPlayPolicy.parseSlideRace(parsedBody)?.let { return@run "Slide race: " + it }
                    GroupPlayPolicy.parseFadeCircle(parsedBody)?.let { return@run "Fade circle: " + it }
                    GroupPlayPolicy.parseSpringDash(parsedBody)?.let { return@run "Spring dash: " + it }
                    GroupPlayPolicy.parseSnapGuard(parsedBody)?.let { return@run "Snap guard: " + it }
                    GroupPlayPolicy.parseRecentsHide(parsedBody)?.let { return@run "Recents hide: " + it }
                    GroupPlayPolicy.parseShieldSprint(parsedBody)?.let { return@run "Shield sprint: " + it }
                    GroupPlayPolicy.parseCopyLock(parsedBody)?.let { return@run "Copy lock: " + it }
                    GroupPlayPolicy.parseExportSeal(parsedBody)?.let { return@run "Export seal: " + it }
                    GroupPlayPolicy.parseLeakWall(parsedBody)?.let { return@run "Leak wall: " + it }
                    GroupPlayPolicy.parseForwardSeal(parsedBody)?.let { return@run "Forward seal: " + it }
                    GroupPlayPolicy.parseChatExportLock(parsedBody)?.let { return@run "Chat export lock: " + it }
                    GroupPlayPolicy.parseVaultFence(parsedBody)?.let { return@run "Vault fence: " + it }
                    GroupPlayPolicy.parseSealSprint(parsedBody)?.let { return@run "Seal sprint: " + it }
                    GroupPlayPolicy.parsePqxdhDash(parsedBody)?.let { return@run "PQXDH dash: " + it }
                    GroupPlayPolicy.parseCertRelay(parsedBody)?.let { return@run "Cert relay: " + it }
                    GroupPlayPolicy.parseMarkSprint(parsedBody)?.let { return@run "Mark sprint: " + it }
                    GroupPlayPolicy.parseFadeTimer(parsedBody)?.let { return@run "Fade timer: " + it }
                    GroupPlayPolicy.parseStampRelay(parsedBody)?.let { return@run "Stamp relay: " + it }
                    GroupPlayPolicy.parseLinkLock(parsedBody)?.let { return@run "Link lock: " + it }
                    GroupPlayPolicy.parsePreviewMute(parsedBody)?.let { return@run "Preview mute: " + it }
                    GroupPlayPolicy.parseUrlFence(parsedBody)?.let { return@run "URL fence: " + it }
                    GroupPlayPolicy.parseNotifMask(parsedBody)?.let { return@run "Notif mask: " + it }
                    GroupPlayPolicy.parseListBlur(parsedBody)?.let { return@run "List blur: " + it }
                    GroupPlayPolicy.parseTraySeal(parsedBody)?.let { return@run "Tray seal: " + it }
                    null
                }
                if (playLabel != null) {
                    Text(
                        text = playLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    return@Column
                }
                val markdownAllowed = RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.MARKDOWN)
                val linkContext = LocalContext.current
                val useMarkdown = markdownAllowed && (
                    message.type == MessageType.MARKDOWN ||
                    parsedMeta.markdown ||
                    ChatMarkdown.looksLikeMarkdown(displayBody)
                )
                if (useMarkdown) {
                    MarkdownMessageContent(
                        text = displayBody,
                        isOwnMessage = isOwnMessage,
                        allowSelection = secretChatId.isNullOrBlank(),
                        onLinkClick = { url ->
                            if (!secretChatId.isNullOrBlank() &&
                                RuntimeFlags.isEnabled(linkContext, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)
                            ) {
                                android.widget.Toast.makeText(
                                    linkContext,
                                    linkContext.getString(com.maodouchat.R.string.secret_external_link_blocked),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@MarkdownMessageContent
                            }
                            com.maodouchat.navigation.AppLinkOpener.openUserFacingUrl(linkContext, url)
                        }
                    )
                } else {
                    RichTextContent(
                        // 9.146：正文为空白时不得回退渲染原始 content——其中含 <meta> JSON
                        //（附件解密密钥/转写文本等），曾整块显示在气泡上
                        text = displayBody,
                        mentionedUserIds = mentionedUserIds,
                        isOwnMessage = isOwnMessage,
                        onContactCardClick = onContactCardClick,
                        // 9.266：TG 式内嵌时间戳——非 Markdown 文本消息时间戳随行尾渲染
                        inlineTimeSuffix = formatTime(message.timestamp),
                        onLinkClick = { url ->
                            // 1.17：名片点击 → 打开该用户资料
                            if (url.startsWith("contactcard://")) {
                                val cardUserId = url.removePrefix("contactcard://")
                                if (onContactCardClick != null) {
                                    onContactCardClick(cardUserId)
                                } else {
                                    android.widget.Toast.makeText(linkContext, linkContext.getString(com.maodouchat.R.string.chat_contact_card_tap_hint), android.widget.Toast.LENGTH_SHORT).show()
                                }
                                return@RichTextContent
                            }
                            if (!secretChatId.isNullOrBlank() &&
                                RuntimeFlags.isEnabled(linkContext, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)
                            ) {
                                android.widget.Toast.makeText(
                                    linkContext,
                                    linkContext.getString(com.maodouchat.R.string.secret_external_link_blocked),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@RichTextContent
                            }
                            com.maodouchat.navigation.AppLinkOpener.openUserFacingUrl(linkContext, url)
                        }
                    )
                }
            }

            LinkPreviewSlot(
                messageContent = displayBody,
                isOwnMessage = isOwnMessage,
                secretChat = !secretChatId.isNullOrBlank(),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = bubbleMaxWidth)
            )

            if (isTranslating || !translationText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isOwnMessage) LocalChatBubbleColor.current.copy(alpha = 0.58f) else palette.chatInputBackground)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    // 稳定尺寸：翻译进度占位恒为 20dp（14dp 转圈 + 6dp 间距），翻译开始/结束时气泡宽度不再跳变
                    Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
                        if (isTranslating) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = if (isOwnMessage) LocalSentBubbleContent.current else Primary)
                    }
                    Text(
                        text = translationText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_translating),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                    )
                }
            }

            if (!safetyWarning.isNullOrBlank()) {
                val dismissInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val dismissPressed by dismissInteractionSource.collectIsPressedAsState()
                val dismissScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (dismissPressed) 0.9f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 480f),
                    label = "safetyDismissScale"
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Error.copy(alpha = if (isOwnMessage) 0.18f else 0.10f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = safetyWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                        )
                        if (onDismissSafety != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.chat_safety_dismiss),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .graphicsLayer { scaleX = dismissScale; scaleY = dismissScale }
                                    .clickable(
                                        interactionSource = dismissInteractionSource,
                                        indication = androidx.compose.material3.ripple(),
                                        onClick = onDismissSafety
                                    )
                            )
                        }
                    }
                }
            }

            // @ 提示（"@我"）
            if (mentionedUserIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(R.plurals.message_mentions_count, mentionedUserIds.size, mentionedUserIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val kb = presentation.meta.inlineKeyboard
            if (kb.isNotEmpty()) {
                InlineKeyboardGrid(
                    rows = kb,
                    isOwnMessage = isOwnMessage,
                    messageId = message.id,
                    onClick = onInlineKeyboardClick
                )
            }

            // 时间 + 状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            ) {
                if (message.starred) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.chat_starred_status),
                        tint = if (isOwnMessage) LocalSentBubbleContentSecondary.current else Primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                if (presentation.meta.silent) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                // 9.266：非 Markdown 文本消息时间戳已内嵌正文行尾，此处不重复；
                // Markdown/其它类型仍走底部时间行（判定与正文渲染分支同构）
                val timeInline = !(RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.MARKDOWN) && (
                    message.type == MessageType.MARKDOWN ||
                        presentation.meta.markdown ||
                        ChatMarkdown.looksLikeMarkdown(displayBody)
                    ))
                if (!timeInline) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
                    )
                }
                DisappearCountdownLabel(expiresAt = message.expiresAt, isOwnMessage = isOwnMessage)
                if (message.editedAt != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.message_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
                    )
                }
                if (isOwnMessage && showStatusIcon) {
                    Spacer(modifier = Modifier.width(4.dp))
                    if (onStatusClick != null) {
                        Box(modifier = Modifier.clickable { onStatusClick(message) }) {
                            MessageStatusIcon(message.status)
                        }
                    } else {
                        MessageStatusIcon(message.status)
                    }
                }
            }
            }
        }
        if (onReply != null && message.type != MessageType.SYSTEM) {
            androidx.compose.material3.TextButton(
                onClick = { onReply(message) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                modifier = Modifier.padding(start = if (isOwnMessage) 0.dp else 44.dp)
            ) {
                Text(
                    stringResource(R.string.message_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
                )
            }
        }
        ReactionSummaryRow(
            message = message,
            currentUserId = currentUserId,
            isOwnMessage = isOwnMessage,
            onReactionClick = onReactionClick
        )
    }
}
