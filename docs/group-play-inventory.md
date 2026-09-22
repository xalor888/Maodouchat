# GroupPlayPolicy 死代码清单（**供产品决策**，不是删除建议）

> 本文件由 G221b 生成。数据来源**不是手工统计**，而是项目里那两条棘轮门禁
> 自行报出的结果（`GroupPlayPolicyTest.unreferenced members only shrink`
> 与 `unreferenced vals only shrink`）——临时把基线改成错误值、让门禁失败并打印全量清单，
> 再原样恢复。口径与门禁完全一致（剥注释、全仓 5 个源码树、定义行计数）。

## 结论一句话

`GroupPlayPolicy.kt` 共 **715** 个声明（542 `fun` + 173 `val`），其中 **470** 个
在全仓库（1580+ 个 .kt，含 app/server/core/domain/feature 与测试）**零引用**：

- `fun` 成员：**297** 个（棘轮基线 `UNREFERENCED_BASELINE = 297`）
- `val`/`var` 声明：**173** 个（棘轮基线 `UNREFERENCED_VAL_BASELINE = 173`，**100% 死**）

这些名字高度集中在几个「群玩法」上（转盘、宾果、抛硬币、猜谜、记忆配对、
你画我猜、数字炸弹、反应赛……），**看起来像一条内容路线图**：骨架和格式解析都写好了，
但没有一个调用方。删不删是产品决策，本文件只把决策面摊开。

## 怎么用本文件

1. 想要哪个玩法 → 找到它的 fun/val → 接线（UI → ViewModel → 这些函数）；
2. 不想要 → 删掉对应条目，然后**把两条棘轮的基线同步调小**（门禁会提示这是「好事」）；
3. 什么都不做 → 现状被两条棘轮冻结，数字只许降不许升。

## `fun` 成员（按名字关键词粗分，仅供浏览）

| 分组 | 条数 | 成员 |
|---|---|---|
| 其它 | 101 | `randomAcrostic`, `randomAlphabetLetter`, `randomAlphabetStart`, `randomAssistCircle`, `randomBeepDash`, `randomBlindQ`, `randomBlurBattle`, `randomCapsule`, `randomCategory`, `randomChatExportLock`, `randomClipDash`, `randomCodeBreaker`, `randomCodeCheck`, `randomColorWord`, `randomCopyLock`, `randomDare`, `randomDecisionDash`, `randomDocHunt`, `randomDownloadDash`, `randomExportSeal`, `randomFadeCircle`, `randomFadeTimer`, `randomFallbackDash`, `randomForwardSeal`, `randomFrameHunt`, `randomGeoClue`, `randomGratitudeRound`, `randomHotPotatoSeconds`, `randomIcebreaker`, `randomKaraoke`, `randomLastSeenSeal`, `randomLeakWall`, `randomLightning`, `randomLinkHunt`, `randomLinkLock`, `randomListBlur`, `randomMapDash`, `randomMarkHunt`, `randomMentionMayhem`, `randomMetaFence`, `randomMirrorEcho`, `randomMirrorLine`, `randomMoodMeter`, `randomNeverHave`, `randomNotifMask`, `randomNudgeDash`, `randomOddOne`, `randomOfflineHint`, `randomOneWord`, `randomPasswordHint`, `randomPinDrop`, `randomPinTheMood`, `randomPixelQuest`, `randomPqxdhDash`, `randomPresenceSeal`, `randomPreviewMask`, `randomPreviewMute`, `randomQrQuest`, `randomQuietHour`, `randomRapidTopic`, `randomReadSeal`, `randomRecentsHide`, `randomRemindCircle`, `randomRevokeRush`, `randomRhymeSeed`, `randomRingChoir`, `randomRingDash`, `randomScatter`, `randomSecretSignal`, `randomSilentMovie`, `randomSillyLaw`, `randomSimonSequence`, `randomSnapGuard`, `randomSoundWave`, `randomSpringDash`, `randomSpyLocation`, `randomStarSeal`, `randomStoryOpener`, `randomStorySeed`, `randomSuggestCircle`, `randomSummaryCircle`, `randomSyncClap`, `randomTaboo`, `randomTalkTopic`, `randomTempoTap`, `randomToast`, `randomTraySeal`, `randomTwentySubject`, `randomTwoWords`, `randomTypingSeal`, `randomUnreadRush`, `randomUrlFence`, `randomVaultFence`, `randomVaultLock`, `randomVideoStage`, `randomVoiceRing`, `randomWallPick`, `randomWatermarkHunt`, `randomWhisper`, `randomWordHint`, `randomWordScramble` |
| 群管/格式 | 79 | `formatAcrostic`, `formatAnonBox`, `formatBeepDash`, `formatBlindQ`, `formatBlurBattle`, `formatCategories`, `formatClipDash`, `formatCodeBreaker`, `formatCodeCheck`, `formatCopyLock`, `formatDocHunt`, `formatDownloadDash`, `formatExportSeal`, `formatFadeCircle`, `formatFadeTimer`, `formatFallbackDash`, `formatForwardSeal`, `formatFrameHunt`, `formatKaraoke`, `formatLeakWall`, `formatLinkHunt`, `formatMapDash`, `formatMarkHunt`, `formatMentionMayhem`, `formatMinuteTalk`, `formatMirror`, `formatMirrorEcho`, `formatMoodMeter`, `formatNudgeDash`, `formatOddOneOut`, `formatOfflineHint`, `formatOneWord`, `formatPasswordGame`, `formatPinDrop`, `formatPinTheMood`, `formatPixelQuest`, `formatPqxdhDash`, `formatPreviewMask`, `formatQrQuest`, `formatQuietHour`, `formatRapidFire`, `formatRecentsHide`, `formatRedPacketJoke`, `formatRemindCircle`, `formatRevokeRush`, `formatRhyme`, `formatRingChoir`, `formatRingDash`, `formatScatter`, `formatSecretSignal`, `formatSillyLaw`, `formatSnapGuard`, `formatSoundWave`, `formatSpringDash`, `formatSpyfall`, `formatStorySeed`, `formatStorySwap`, `formatSuggestCircle`, `formatSummaryCircle`, `formatSyncClap`, `formatTaboo`, `formatTempoTap`, `formatTimeCapsule`, `formatToast`, `formatTwoWords`, `formatUnreadRush`, `formatVaultFence`, `formatVaultLock`, `formatVideoStage`, `formatVoiceRing`, `formatWallPick`, `formatWatermarkHunt`, `formatWordHint`, `parseBlindQ`, `parseKaraoke`, `parseMinuteTalk`, `parseRedPacketJoke`, `parseScatter`, `parseStorySwap` |
| 反应/竞速 | 62 | `formatAlertSprint`, `formatAlphabetRace`, `formatBuzzRelay`, `formatCertRelay`, `formatClickBeat`, `formatCountdownRace`, `formatFeelSprint`, `formatFileRelay`, `formatFocusSprint`, `formatFontRace`, `formatGifRelay`, `formatIdeaRelay`, `formatInviteRace`, `formatLeakSprint`, `formatMarkSprint`, `formatPhotoRace`, `formatPushRace`, `formatReplySprint`, `formatRewriteRelay`, `formatScanSprint`, `formatSealSprint`, `formatSecureSprint`, `formatShieldSprint`, `formatSlideRace`, `formatSpoilerRace`, `formatStampRelay`, `formatThemeSprint`, `formatTrustSprint`, `formatVoiceRace`, `formatWakeSprint`, `randomAlertSprint`, `randomBuzzRelay`, `randomCertRelay`, `randomClickBeat`, `randomCountdownRace`, `randomFeelSprint`, `randomFileRelay`, `randomFocusSprint`, `randomFontRace`, `randomGifRelay`, `randomIdeaRelay`, `randomInsightSprint`, `randomInviteRace`, `randomLeakSprint`, `randomMarkSprint`, `randomMeaningRace`, `randomPhotoRace`, `randomPushRace`, `randomReplySprint`, `randomRewriteRelay`, `randomScanSprint`, `randomSealSprint`, `randomSecureSprint`, `randomShieldSprint`, `randomSlideRace`, `randomSpoilerRace`, `randomStampRelay`, `randomThemeSprint`, `randomTranslateRelay`, `randomTrustSprint`, `randomVoiceRace`, `randomWakeSprint` |
| 宾果/记忆配对 | 18 | `formatChainReact`, `formatChatExportLock`, `formatEmojiMath`, `formatEmojiMemory`, `formatEmojiTranslate`, `formatHideSeek`, `formatHotPotato`, `formatMemoryMatch`, `formatSpeedMath`, `parseMemoryMatch`, `randomBingoBoard`, `randomEmojiMath`, `randomEmojiMemory`, `randomEmojiOnly`, `randomEmojiStory`, `randomEmojiStorySeed`, `randomEmojiTr`, `randomMathQ` |
| 猜谜/问答 | 16 | `formatDebate`, `formatDebateFlash`, `formatFactOrFiction`, `formatGeoGuess`, `formatTwentyQuestions`, `formatWould2`, `ormatTruthPrompt`, `randomDebateFlash`, `randomDebateTopic`, `randomEmojiQuiz`, `randomFactOrFiction`, `randomQuiz`, `randomRiddle`, `randomTrivia`, `randomWould2`, `randomWouldPair` |
| 你画我猜/表演 | 10 | `formatCaptionThis`, `formatContactSwap`, `formatPromptSprint`, `parseCaptionThis`, `randomCaptionSeed`, `randomCharadesPrompt`, `randomContactSwap`, `randomPromptSprint`, `randomReactLock`, `randomReactionDuel` |
| 转盘/抽奖 | 7 | `formatBlindDraw`, `formatImpulseDraw`, `randomBlindDraw`, `randomDrawPrompt`, `randomFortune`, `randomImpulseDraw`, `spinWheel` |
| 骰子/抛硬币 | 2 | `flipCoin`, `rollNumberGuess` |
| 数字炸弹/接龙 | 2 | `formatQuickPoll`, `randomQuickPoll` |

## `val`/`var` 声明（173 个，全部是 `*_PREFIX`）

这些是各玩法的指令前缀常量，**没有任何一个被引用**。按字母序列出：

```
  ACROSTIC_PREFIX               ALERT_SPRINT_PREFIX           ALPHABET_PREFIX               ALPHABET_RACE_PREFIX          ANON_PREFIX                   ASSIST_CIRCLE_PREFIX        
  BEEP_DASH_PREFIX              BINGO_PREFIX                  BLIND_DRAW_PREFIX             BLIND_Q_PREFIX                BLUR_BATTLE_PREFIX            BOMB_PREFIX                 
  BUZZ_RELAY_PREFIX             CAPTION_THIS_PREFIX           CATEGORIES_PREFIX             CERT_RELAY_PREFIX             CHAIN_REACT_PREFIX            CHARADES_PREFIX             
  CHAT_EXPORT_LOCK_PREFIX       CHECKIN_PREFIX                CLICK_BEAT_PREFIX             CLIP_DASH_PREFIX              CODE_BREAKER_PREFIX           CODE_CHECK_PREFIX           
  COINFLIP_PREFIX               COLOR_WORD_PREFIX             CONTACT_SWAP_PREFIX           COPY_LOCK_PREFIX              COUNTDOWN_PREFIX              COUNTDOWN_RACE_PREFIX       
  DEBATE_FLASH_PREFIX           DEBATE_PREFIX                 DECISION_DASH_PREFIX          DICE_PREFIX                   DOC_HUNT_PREFIX               DOWNLOAD_DASH_PREFIX        
  DRAW_PROMPT_PREFIX            EMOJI_DUEL_PREFIX             EMOJI_MATH_PREFIX             EMOJI_MEMORY_PREFIX           EMOJI_ONLY_PREFIX             EMOJI_QUIZ_PREFIX           
  EMOJI_RAIN_PREFIX             EMOJI_STORY_PREFIX            EMOJI_TR_PREFIX               EXPORT_SEAL_PREFIX            FACT_OR_FICTION_PREFIX        FADE_CIRCLE_PREFIX          
  FADE_TIMER_PREFIX             FALLBACK_DASH_PREFIX          FEEL_SPRINT_PREFIX            FILE_RELAY_PREFIX             FOCUS_SPRINT_PREFIX           FONT_RACE_PREFIX            
  FORTUNE_PREFIX                FORWARD_SEAL_PREFIX           FRAME_HUNT_PREFIX             GEO_GUESS_PREFIX              GIF_RELAY_PREFIX              GRATITUDE_ROUND_PREFIX      
  HIDESEEK_PREFIX               HOTORNOT_PREFIX               HOTPOTATO_PREFIX              HOTSEAT_PREFIX                ICEBREAKER_PREFIX             IDEA_RELAY_PREFIX           
  IMPOSTOR_PREFIX               IMPULSE_DRAW_PREFIX           INSIGHT_SPRINT_PREFIX         INVITE_RACE_PREFIX            KARAOKE_PREFIX                LEAK_SPRINT_PREFIX          
  LEAK_WALL_PREFIX              LIGHTNING_PREFIX              LINK_HUNT_PREFIX              LOTTERY_PREFIX                MAP_DASH_PREFIX               MARK_HUNT_PREFIX            
  MARK_SPRINT_PREFIX            MEANING_RACE_PREFIX           MEMORY_MATCH_PREFIX           MENTION_MAYHEM_PREFIX         MINUTE_TALK_PREFIX            MIRROR_ECHO_PREFIX          
  MIRROR_PREFIX                 MOOD_METER_PREFIX             NEVER_HAVE_PREFIX             NUDGE_DASH_PREFIX             NUMBERGUESS_PREFIX            ODDONE_PREFIX               
  OFFLINE_HINT_PREFIX           ONE_WORD_PREFIX               PASSWORD_PREFIX               PHOTO_RACE_PREFIX             PIN_DROP_PREFIX               PIN_THE_MOOD_PREFIX         
  PIXEL_QUEST_PREFIX            POLL_PREFIX                   PQXDH_DASH_PREFIX             PREVIEW_MASK_PREFIX           PROMPT_SPRINT_PREFIX          PUSH_RACE_PREFIX            
  QR_QUEST_PREFIX               QUICK_POLL_PREFIX             QUIET_HOUR_PREFIX             QUIZ_PREFIX                   RACE_PREFIX                   RAPID_FIRE_PREFIX           
  REACTION_DUEL_PREFIX          RECENTS_HIDE_PREFIX           REDPACKET_PREFIX              REMIND_CIRCLE_PREFIX          REPLY_SPRINT_PREFIX           REVOKE_RUSH_PREFIX          
  REWRITE_RELAY_PREFIX          RHYME_PREFIX                  RIDDLE_PREFIX                 RING_CHOIR_PREFIX             RING_DASH_PREFIX              RPS_PREFIX                  
  SCAN_SPRINT_PREFIX            SCATTER_PREFIX                SEAL_SPRINT_PREFIX            SECRET_SIGNAL_PREFIX          SECURE_SPRINT_PREFIX          SHIELD_SPRINT_PREFIX        
  SILENT_MOVIE_PREFIX           SILLY_LAW_PREFIX              SIMON_PREFIX                  SLIDE_RACE_PREFIX             SNAP_GUARD_PREFIX             SOUND_WAVE_PREFIX           
  SPEED_MATH_PREFIX             SPEED_PREFIX                  SPIN_PREFIX                   SPOILER_RACE_PREFIX           SPRING_DASH_PREFIX            SPYFALL_PREFIX              
  STAMP_RELAY_PREFIX            STORY_PREFIX                  STORY_SEED_PREFIX             STORY_SWAP_PREFIX             SUGGEST_CIRCLE_PREFIX         SUMMARY_CIRCLE_PREFIX       
  SYNC_CLAP_PREFIX              TABOO_PREFIX                  TEMPO_TAP_PREFIX              THEME_SPRINT_PREFIX           TIMECAPSULE_PREFIX            TOAST_PREFIX                
  TRANSLATE_RELAY_PREFIX        TRIVIA_PREFIX                 TRUST_SPRINT_PREFIX           TRUTHS_PREFIX                 TRUTH_OR_DARE_PREFIX          TRUTH_PREFIX                
  TWENTYQ_PREFIX                TWO_WORDS_PREFIX              UNREAD_RUSH_PREFIX            VAULT_FENCE_PREFIX            VAULT_LOCK_PREFIX             VIDEO_STAGE_PREFIX          
  VOICE_RACE_PREFIX             VOICE_RING_PREFIX             WAKE_SPRINT_PREFIX            WALL_PICK_PREFIX              WATERMARK_HUNT_PREFIX         WHISPER_PREFIX              
  WORDHINT_PREFIX               WORD_PREFIX                   WORD_SCRAMBLE_PREFIX          WOULD_PREFIX                  WOULD_YOU_PREFIX2           
```

## 门禁

| 门禁 | 位置 | 语义 |
|---|---|---|
| `unreferenced members only shrink` | `app/src/test/java/com/maodouchat/util/GroupPlayPolicyTest.kt` | 死 `fun` 数只能降；下降要同步下调基线 |
| `unreferenced vals only shrink` | 同上 | 死 `val` 数只能降（G216b 新增，基线 173） |
| `GroupPlayPolicy exists exactly once in main sources` | `ClientArchitectureTest` | 该类不得被复制出第二份 |
