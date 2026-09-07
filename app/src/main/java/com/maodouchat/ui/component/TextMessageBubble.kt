package com.maodouchat.ui.component

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.maodouchat.network.TokenManager
import com.maodouchat.network.ApiService
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.maodouchat.ui.component.OwnerScopedImageKeys
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import com.maodouchat.util.LinkPreviewPolicy
import com.maodouchat.util.LinkPreviewPreferences
import com.maodouchat.util.LinkPreviewRepository
import com.maodouchat.util.MediaCache
import com.maodouchat.ui.theme.Error
import androidx.compose.ui.graphics.Brush
import com.maodouchat.ui.theme.LocalChatBubbleColor
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.TextWhite
import com.maodouchat.ui.theme.TextWhiteSecondary
import com.maodouchat.ui.theme.UnreadRed
import java.util.Locale

// ─── resolveBubbleShape ───
@Composable
internal fun resolveBubbleShape(isOwnMessage: Boolean, isGroupEdge: Boolean): androidx.compose.ui.graphics.Shape {
    val base = if (isOwnMessage) com.maodouchat.ui.theme.LocalBubbleShapes.current.sent
    else com.maodouchat.ui.theme.LocalBubbleShapes.current.received
    val rounded = base as? androidx.compose.foundation.shape.RoundedCornerShape ?: return base
    val tight = androidx.compose.foundation.shape.CornerSize(4.5.dp)
    return if (isOwnMessage) {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = rounded.topStart,
            topEnd = if (!isGroupEdge) tight else rounded.topEnd,
            bottomStart = rounded.bottomStart,
            bottomEnd = if (isGroupEdge) tight else rounded.bottomEnd
        )
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (!isGroupEdge) tight else rounded.topStart,
            topEnd = rounded.topEnd,
            bottomStart = if (isGroupEdge) tight else rounded.bottomStart,
            bottomEnd = rounded.bottomEnd
        )
    }
}


// ─── TextBubble ───
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

            // 引用预览（如果有）
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

            // 气泡
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
                val dice = com.maodouchat.util.GroupPlayPolicy.parseDice(displayBody)
                if (dice != null) {
                    val (sides, value) = dice
                    Text(
                        text = "🎲 $value / $sides",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
                    )
                    return@Column
                }
                val lucky = com.maodouchat.util.GroupPlayPolicy.parseLuckyDraw(displayBody)
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
                if (com.maodouchat.util.CaptureAlertPolicy.isCaptureAlert(parsedBody)) {
                    // 8.49 防御：解析失败直接跳过（此前 parse()!! 依赖「isCaptureAlert 与 parse 永远一致」的脆弱不变量）
                    val parsedAlert = com.maodouchat.util.CaptureAlertPolicy.parse(parsedBody) ?: return@Column
                    val (_, detail) = parsedAlert
                    Text(
                        text = "ALERT: $detail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage) LocalSentBubbleContent.current else Error,
                        fontWeight = FontWeight.SemiBold
                    )
                    return@Column
                }
                val poll = com.maodouchat.util.GroupPlayPolicy.parsePoll(parsedBody)
                if (poll != null) {
                    InteractivePollCard(
                        pollJson = poll,
                        isOwnMessage = isOwnMessage,
                        onVote = onPollVote
                    )
                    return@Column
                }
                val playLabel = run {
                    com.maodouchat.util.GroupPlayPolicy.parseDice(parsedBody)?.let { (sides, value) -> return@run "Dice $value / $sides" }
                    com.maodouchat.util.GroupPlayPolicy.parseRps(parsedBody)?.let { return@run "RPS: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLuckyDraw(parsedBody)?.let { (picker, target) -> return@run "Lucky: $picker -> $target" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.CHECKIN_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.CHECKIN_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.TRUTH_PREFIX)) { return@run "Truth: " + parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.TRUTH_PREFIX).substringAfter('|', parsedBody) }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.ANON_PREFIX)) { return@run "Anon: " + parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.ANON_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseReactionRace(parsedBody)?.let { (token, label) -> return@run "Race $token: $label" }
                    com.maodouchat.util.GroupPlayPolicy.parseNumberBomb(parsedBody)?.let { (max, _, label) -> return@run "Bomb 1-$max: $label" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.WORD_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.WORD_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseWouldYouRather(parsedBody)?.let { (a, b, _) -> return@run "Would you rather: $a  OR  $b" }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.EMOJI_RAIN_PREFIX)) { return@run parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.EMOJI_RAIN_PREFIX).substringAfter('|', parsedBody) }
                    com.maodouchat.util.GroupPlayPolicy.parseTwoTruthsOneLie(parsedBody)?.let { items -> return@run "Two truths & one lie: " + items.joinToString(" / ") }
                    if (parsedBody.startsWith(com.maodouchat.util.GroupPlayPolicy.QUIZ_PREFIX)) {
                        // 9.224：走 parseQuiz 拿到 unesc 后的题目（直接 substringBefore 会残留转义符）
                        val q = com.maodouchat.util.GroupPlayPolicy.parseQuiz(parsedBody)?.first
                            ?: parsedBody.removePrefix(com.maodouchat.util.GroupPlayPolicy.QUIZ_PREFIX).substringBefore('|')
                        return@run "Quiz: $q"
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseCharades(parsedBody)?.let { return@run "Charades: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseNumberGuess(parsedBody)?.let { (_, max) -> return@run "Number guess 1..$max" }
                    com.maodouchat.util.GroupPlayPolicy.parseRiddle(parsedBody)?.let { (q, _) -> return@run "Riddle: $q" }
                    com.maodouchat.util.GroupPlayPolicy.parseImpostor(parsedBody)?.let { return@run "Impostor game started" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSimon(parsedBody)?.let { return@run "Simon: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHotOrNot(parsedBody)?.let { return@run "Hot or not: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseAlphabet(parsedBody)?.let { return@run "Alphabet ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTrivia(parsedBody)?.let { (q, _) -> return@run "Trivia: $q" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpeedChallenge(parsedBody)?.let { return@run "Speed ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseFortune(parsedBody)?.let { return@run "Fortune: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiQuiz(parsedBody)?.let { (p, _) -> return@run "Emoji quiz: $p" }
                    com.maodouchat.util.GroupPlayPolicy.parseChainReact(parsedBody)?.let { return@run "Chain: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDebate(parsedBody)?.let { return@run "Debate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMirror(parsedBody)?.let { return@run "Mirror: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHideSeek(parsedBody)?.let { return@run "Hide&Seek: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseToast(parsedBody)?.let { return@run "Roast: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseHotPotato(parsedBody)?.let { return@run "Hot potato ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseWordHint(parsedBody)?.let { (h, _) -> return@run "Word hint: $h" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpyfall(parsedBody)?.let { return@run "Spyfall @ ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseAcrostic(parsedBody)?.let { return@run "Acrostic: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiTranslate(parsedBody)?.let { (p, _) -> return@run "Emoji TR: $p" }
                    com.maodouchat.util.GroupPlayPolicy.parseTwentyQuestions(parsedBody)?.let { return@run "20Q: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRhyme(parsedBody)?.let { return@run "Rhyme: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseOddOneOut(parsedBody)?.let { (o, _) -> return@run "Odd one: $o" }
                    com.maodouchat.util.GroupPlayPolicy.parseCategories(parsedBody)?.let { return@run "Categories: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePasswordGame(parsedBody)?.let { return@run "Password game: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTimeCapsule(parsedBody)?.let { return@run "Capsule: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTaboo(parsedBody)?.let { return@run "Taboo: ${it.substringBefore('|')}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLightning(parsedBody)?.let { return@run "Lightning: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTwoWords(parsedBody)?.let { return@run "Two words: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWhisper(parsedBody)?.let { return@run "Whisper: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiDuel(parsedBody)?.let { return@run "Emoji duel: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCountdownRace(parsedBody)?.let { return@run "Race ${it}s" }
                    com.maodouchat.util.GroupPlayPolicy.parseRapidFire(parsedBody)?.let { return@run "Rapid: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiMemory(parsedBody)?.let { return@run "Memory: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseGeoGuess(parsedBody)?.let { return@run "Geo: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseOneWord(parsedBody)?.let { return@run "One word: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpeedMath(parsedBody)?.let { return@run "Math: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseStorySeed(parsedBody)?.let { return@run "Story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWould2(parsedBody)?.let { (a, b) -> return@run "Would you: $a OR $b" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiOnly(parsedBody)?.let { return@run "Emoji only: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseBlindDraw(parsedBody)?.let { return@run "Blind draw" }
                    com.maodouchat.util.GroupPlayPolicy.parseAlphabetRace(parsedBody)?.let { return@run "Alphabet: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSilentMovie(parsedBody)?.let { return@run "Silent movie: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseColorWord(parsedBody)?.let { return@run "Color word: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDebateFlash(parsedBody)?.let { return@run "Debate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiStory(parsedBody)?.let { return@run "Emoji story: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseQuickPoll(parsedBody)?.let { return@run "Quick poll: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMirrorEcho(parsedBody)?.let { return@run "Mirror: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSyncClap(parsedBody)?.let { return@run "Sync clap x${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFactOrFiction(parsedBody)?.let { return@run "Fact?: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseImpulseDraw(parsedBody)?.let { return@run "Impulse: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWordScramble(parsedBody)?.let { return@run "Scramble: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseReactionDuel(parsedBody)?.let { return@run "React duel: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCodeBreaker(parsedBody)?.let { return@run "Code breaker" }
                    com.maodouchat.util.GroupPlayPolicy.parseSillyLaw(parsedBody)?.let { return@run "Law: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseEmojiMath(parsedBody)?.let { return@run "Emoji math: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePinTheMood(parsedBody)?.let { return@run "Mood pin: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRevokeRush(parsedBody)?.let { return@run "Revoke rush ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSecretSignal(parsedBody)?.let { return@run "Signal: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMoodMeter(parsedBody)?.let { return@run "Mood meter: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFocusSprint(parsedBody)?.let { return@run "Focus sprint ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseGratitudeRound(parsedBody)?.let { return@run "Gratitude: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseIdeaRelay(parsedBody)?.let { return@run "Idea relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTempoTap(parsedBody)?.let { return@run "Tempo tap ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTranslateRelay(parsedBody)?.let { return@run "Translate: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseInviteRace(parsedBody)?.let { return@run "Invite race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMentionMayhem(parsedBody)?.let { return@run "Mention mayhem: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseLinkHunt(parsedBody)?.let { return@run "Link hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseNudgeDash(parsedBody)?.let { return@run "Nudge dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseCodeCheck(parsedBody)?.let { return@run "Code check: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseTrustSprint(parsedBody)?.let { return@run "Trust sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseQrQuest(parsedBody)?.let { return@run "QR quest: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseContactSwap(parsedBody)?.let { return@run "Contact swap: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseScanSprint(parsedBody)?.let { return@run "Scan sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSpoilerRace(parsedBody)?.let { return@run "Spoiler race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseBlurBattle(parsedBody)?.let { return@run "Blur battle: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseDownloadDash(parsedBody)?.let { return@run "Download dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePinDrop(parsedBody)?.let { return@run "Pin drop: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFileRelay(parsedBody)?.let { return@run "File relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseMapDash(parsedBody)?.let { return@run "Map dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseVaultLock(parsedBody)?.let { return@run "Vault lock: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseWatermarkHunt(parsedBody)?.let { return@run "Watermark hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSecureSprint(parsedBody)?.let { return@run "Secure sprint: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePhotoRace(parsedBody)?.let { return@run "Photo race: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseClipDash(parsedBody)?.let { return@run "Clip dash: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseFrameHunt(parsedBody)?.let { return@run "Frame hunt: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseSummaryCircle(parsedBody)?.let { return@run "Summary circle: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parseRewriteRelay(parsedBody)?.let { return@run "Rewrite relay: ${it}" }
                    com.maodouchat.util.GroupPlayPolicy.parsePromptSprint(parsedBody)?.let { return@run "Prompt sprint: ${it}"
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseSuggestCircle(parsedBody)?.let { return@run "Suggest circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVoiceRace(parsedBody)?.let { return@run "Voice race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseReplySprint(parsedBody)?.let { return@run "Reply sprint: " + it
                    }
                    com.maodouchat.util.GroupPlayPolicy.parsePixelQuest(parsedBody)?.let { return@run "Pixel quest: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseAssistCircle(parsedBody)?.let { return@run "Assist circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseDecisionDash(parsedBody)?.let { return@run "Decision dash: " + it
                    }
                    com.maodouchat.util.GroupPlayPolicy.parseDocHunt(parsedBody)?.let { return@run "Doc hunt: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMeaningRace(parsedBody)?.let { return@run "Meaning race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseInsightSprint(parsedBody)?.let { return@run "Insight sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseGifRelay(parsedBody)?.let { return@run "Gif relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMarkHunt(parsedBody)?.let { return@run "Mark hunt: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLeakSprint(parsedBody)?.let { return@run "Leak sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVoiceRing(parsedBody)?.let { return@run "Voice ring: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVideoStage(parsedBody)?.let { return@run "Video stage: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRingDash(parsedBody)?.let { return@run "Ring dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseWallPick(parsedBody)?.let { return@run "Wall pick: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFontRace(parsedBody)?.let { return@run "Font race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseThemeSprint(parsedBody)?.let { return@run "Theme sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseUnreadRush(parsedBody)?.let { return@run "Unread rush: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRingChoir(parsedBody)?.let { return@run "Ring choir: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseAlertSprint(parsedBody)?.let { return@run "Alert sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSoundWave(parsedBody)?.let { return@run "Sound wave: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePreviewMask(parsedBody)?.let { return@run "Preview mask: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseBeepDash(parsedBody)?.let { return@run "Beep dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePushRace(parsedBody)?.let { return@run "Push race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRemindCircle(parsedBody)?.let { return@run "Remind circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseWakeSprint(parsedBody)?.let { return@run "Wake sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseQuietHour(parsedBody)?.let { return@run "Quiet hour: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseOfflineHint(parsedBody)?.let { return@run "Offline hint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFallbackDash(parsedBody)?.let { return@run "Fallback dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseClickBeat(parsedBody)?.let { return@run "Click beat: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseBuzzRelay(parsedBody)?.let { return@run "Buzz relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFeelSprint(parsedBody)?.let { return@run "Feel sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSlideRace(parsedBody)?.let { return@run "Slide race: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFadeCircle(parsedBody)?.let { return@run "Fade circle: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSpringDash(parsedBody)?.let { return@run "Spring dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSnapGuard(parsedBody)?.let { return@run "Snap guard: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseRecentsHide(parsedBody)?.let { return@run "Recents hide: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseShieldSprint(parsedBody)?.let { return@run "Shield sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseCopyLock(parsedBody)?.let { return@run "Copy lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseExportSeal(parsedBody)?.let { return@run "Export seal: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLeakWall(parsedBody)?.let { return@run "Leak wall: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseForwardSeal(parsedBody)?.let { return@run "Forward seal: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseChatExportLock(parsedBody)?.let { return@run "Chat export lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseVaultFence(parsedBody)?.let { return@run "Vault fence: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseSealSprint(parsedBody)?.let { return@run "Seal sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePqxdhDash(parsedBody)?.let { return@run "PQXDH dash: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseCertRelay(parsedBody)?.let { return@run "Cert relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseMarkSprint(parsedBody)?.let { return@run "Mark sprint: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseFadeTimer(parsedBody)?.let { return@run "Fade timer: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseStampRelay(parsedBody)?.let { return@run "Stamp relay: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseLinkLock(parsedBody)?.let { return@run "Link lock: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parsePreviewMute(parsedBody)?.let { return@run "Preview mute: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseUrlFence(parsedBody)?.let { return@run "URL fence: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseNotifMask(parsedBody)?.let { return@run "Notif mask: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseListBlur(parsedBody)?.let { return@run "List blur: " + it }
                    com.maodouchat.util.GroupPlayPolicy.parseTraySeal(parsedBody)?.let { return@run "Tray seal: " + it }
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
                            com.maodouchat.ui.navigation.AppLinkOpener.openUserFacingUrl(linkContext, url)
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
                            com.maodouchat.ui.navigation.AppLinkOpener.openUserFacingUrl(linkContext, url)
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
                    if (isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = if (isOwnMessage) LocalSentBubbleContent.current else Primary)
                        Spacer(modifier = Modifier.width(6.dp))
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


// ─── LinkPreviewSlot ───
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun LinkPreviewSlot(
    messageContent: String,
    isOwnMessage: Boolean,
    secretChat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userEnabled = remember(LinkPreviewPreferences.version) { LinkPreviewPreferences.isEnabled(context) }
    val secretBlocksPreview = secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_LINK_PREVIEW_BLOCK)
    val enabled = userEnabled && !secretBlocksPreview
    val url = remember(messageContent, enabled) {
        if (!enabled) null else LinkPreviewPolicy.firstHttpUrl(messageContent)
    }
    if (url == null) return

    var preview by remember(url) {
        mutableStateOf(LinkPreviewRepository.cached(url))
    }

    LaunchedEffect(url) {
        // fetch 自带正/负缓存与 in-flight 去重；失败返回 null
        preview = LinkPreviewRepository.fetch(url)
    }

    val card = preview ?: return
    if (!LinkPreviewPolicy.isUseful(card)) return

    LinkPreviewCard(
        preview = card,
        isOwnMessage = isOwnMessage,
        modifier = modifier,
        onOpen = {
            if (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.maodouchat.R.string.secret_external_link_blocked),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@LinkPreviewCard
            }
            com.maodouchat.ui.navigation.AppLinkOpener.openUserFacingUrl(context, card.url)
        }
    )
}


// ─── LinkPreviewCard ───
@Composable
internal fun LinkPreviewCard(
    preview: LinkPreviewPolicy.Preview,
    isOwnMessage: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalChatPalette.current
    val bg = if (isOwnMessage) {
        LocalChatBubbleColor.current.copy(alpha = 0.55f)
    } else {
        palette.chatInputBackground
    }
    val titleColor = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    val descColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextSecondary
    val hostColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
    val site = preview.siteName?.takeIf { it.isNotBlank() }
        ?: LinkPreviewPolicy.displayHost(preview.url)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onOpen)
            .padding(bottom = 8.dp)
    ) {
        if (!preview.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = OwnerScopedImageKeys.request(
                    context = LocalContext.current,
                    data = preview.imageUrl,
                    sizeWidth = 640,
                    sizeHeight = 360,
                ),
                contentDescription = stringResource(R.string.message_link_preview_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = site,
                style = MaterialTheme.typography.labelSmall,
                color = hostColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!preview.title.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!preview.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = descColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 富文本消息：高亮正文中的 @token（displayName 或遗留 userId）。 */
/** 1.17：从名片标记中提取目标用户 id（供点击打开资料）。 */

// ─── CONTACT_CARD_USER_RE ───
internal val CONTACT_CARD_USER_RE = Regex("\\[contactUser:([^\\]]+)")

// ─── RichTextContent ───
@Composable
internal fun RichTextContent(
    text: String,
    mentionedUserIds: List<String>,
    isOwnMessage: Boolean,
    onContactCardClick: ((String) -> Unit)? = null,
    onLinkClick: (String) -> Unit = {},
    // 9.266：TG 式内嵌时间戳——非空时追加在正文最后一行行尾（小字号次色）
    inlineTimeSuffix: String? = null
) {
    // TG 式行尾时间戳 span：两空格间隔 + 11sp 次色，与正文同段落自然折行
    val inlineTimeColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
    fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineTime(time: String) {
        append("  ")
        withStyle(
            androidx.compose.ui.text.SpanStyle(
                fontSize = 11.sp,
                color = inlineTimeColor
            )
        ) {
            append(time)
        }
    }
    // 1.11：先剥离名片标记，接收端不会看到裸 [contactUser:...]（1.18 复用 ChatMarkdown 统一实现）
    val cleanText = com.maodouchat.ui.component.ChatMarkdown.stripContactCardMarker(text)
    // 1.17：名片消息整体渲染为可点击链接（点击打开该用户资料）
    val cardUserId = remember(text) { CONTACT_CARD_USER_RE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } }
    if (cardUserId != null) {
        val cardUrl = "contactcard://$cardUserId"
        val annotatedCard = androidx.compose.ui.text.buildAnnotatedString {
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Clickable(
                    tag = cardUrl,
                    linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(cardUrl) }
                )
            ) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = if (isOwnMessage) LocalSentBubbleContent.current else androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                ) {
                    append(cleanText.ifBlank { text })
                }
            }
        }
        Text(
            text = annotatedCard,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
        )
        return
    }
    val mentionColor = androidx.compose.ui.graphics.Color(0xFFFFC107)
    val hasAt = cleanText.contains('@')
    val urlRanges = remember(cleanText) { findUrlRanges(cleanText) }
    if (!hasAt && mentionedUserIds.isEmpty() && urlRanges.isEmpty()) {
        if (inlineTimeSuffix == null) {
            Text(
                text = cleanText,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
            )
        } else {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append(cleanText)
                    appendInlineTime(inlineTimeSuffix)
                },
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
            )
        }
        return
    }
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < cleanText.length) {
            // 优先匹配 URL（避免 @ 把 URL 内片段误判为 mention）
            val urlHit = urlRanges.firstOrNull { it.first == i }
            if (urlHit != null) {
                val (start, end) = urlHit
                val url = cleanText.substring(start, end)
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = url,
                        linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(url) }
                    )
                ) {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = mentionColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(url)
                    }
                }
                i = end
                continue
            }
            if (cleanText[i] != '@' || (i > 0 && !cleanText[i - 1].isWhitespace())) {
                append(cleanText[i])
                i++
                continue
            }
            // 从 @ 扫到空白/标点
            var j = i + 1
            while (j < cleanText.length) {
                val ch = cleanText[j]
                if (ch.isWhitespace() || ch == ',' || ch == '.' || ch == '!' || ch == '?' ||
                    ch == '，' || ch == '。' || ch == '！' || ch == '？'
                ) break
                j++
            }
            if (j > i + 1) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = mentionColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                ) {
                    append(cleanText.substring(i, j))
                }
                i = j
            } else {
                append('@')
                i++
            }
        }
        // 9.266：mention/URL 混排分支同样追加行尾时间戳
        if (inlineTimeSuffix != null) appendInlineTime(inlineTimeSuffix)
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    )
}

/** 扫描文本中的 http/https URL 起止区间（左闭右开），供 [RichTextContent] 渲染可点击链接。 */

// ─── findUrlRanges ───
internal fun findUrlRanges(text: String): List<Pair<Int, Int>> {
    val ranges = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < text.length) {
        val start = if (text.startsWith("http://", i) || text.startsWith("https://", i)) i else -1
        if (start < 0) { i++; continue }
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in setOf('<', '>', '"', '\'')) {
            end++
        }
        while (end > start && text[end - 1] in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) {
            end--
        }
        if (end > start) ranges += start to end
        i = end.coerceAtLeast(start + 1)
    }
    return ranges
}

/** 输入框上方的「回复某条消息」提示条 */

// ─── ReplyTargetBar ───
@Composable
fun ReplyTargetBar(senderName: String, preview: String, onCancel: () -> Unit) {
    val palette = LocalChatPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chatInputBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.message_reply_to, senderName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(preview, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textHint, maxLines = 1)
        }
        androidx.compose.material3.TextButton(onClick = onCancel) {
            Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textHint)
        }
    }
}

@Preview(showBackground = true)

// ─── RoleBadge ───
@Composable
internal fun RoleBadge(label: String, owner: Boolean) {
    val background = if (owner) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (owner) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = foreground,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
