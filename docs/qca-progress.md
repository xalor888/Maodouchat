# QCA 无人值守循环进度

## 环境事实（已实测，不要重复探测）
- 换源环境 maodouchat-mirrors：Gradle 8.14.5 已预置，init.d/mirror.gradle 已把依赖源改到腾讯云/阿里云，冷启动到「server 编译完成」约 6 分钟。
- server/ 不在 root build，跑它的测试用 `./gradlew -p server test`，没有 `:server:test` 这个任务。
- 全量 server test >8 分钟（125 个测试文件，每路由重建 H2 schema）→ 每轮验证用类粒度 --tests。
- 长构建必须后台跑 + 轮询日志，工具 60 秒无输出会看门狗误杀。
- app/ 侧 Android 构建尚未实测（SDK 未预装），不得声称已验证。

## 待办队列（按性价比）
1. ChatDetailRoute 第十二批状态收口：延续第十/十一批的做法。当前 2711 行，Route 内还剩 15 处 `mutableStateOf`。
   剩余候选（本轮读代码后的实测清单）：
   - 滚动/高亮族：`lastAutoScrollMessageId`(341)、`navigationHighlightMessageId`(463)、`pendingNewMessageCount`(340, mutableIntStateOf)、`bubbleBounds`(464) —— 语义都是「滚动与高亮去哪一条」。
   - 全屏媒体族：`fullScreenImage`(448)、`fullScreenVideo`(449)。
   - 回复/弹层零散开关：`replyTarget`(453)、`showChatOverflow`(457)、`showGroupInfo`(455)、`showBatchDeleteConfirm`(454)、`showClearHistoryConfirm`(451)、`setLockError`(460) —— 注意后两个与 `ChatDetailChatLockState` 相邻，别重复收。
   - AI 本地安全族：`localSafetyEnabled`(349)、`dismissedSafetyMessageIds`(352) + `dismissSafetyForMessage()`(355) —— 这族和偏好读写同构（第十一批刚收过外观那族，范式可直接复用，且带一个「dismiss 后立刻落盘」的副作用值得收进方法）。
   **注意：本仓库 app/ 在云端无法编译验证，只能静态分析 + 出建议性 PR。**
2. 第十一批的编译验证：本批未跑构建（容器无 Android SDK），需人在本地跑 `:app:testDebugUnitTest` + `:app:assembleDebug` 后方可合并。
3. server/ 模块：挑一个现有测试覆盖薄弱的路由补测（能真跑，可用 --tests 验证）。
4. 确认 docs/ 里是否缺 QCA 循环的使用说明。

## 已完成
- 2026-09-25：写入通道实测（本轮）——推分支 + 开 PR。
- 2026-09-25：第十一批（外观偏好三件套收口，见下）。

## 每轮日志
<!-- 每轮在此追加一行：日期 | 做了什么 | 实际验证命令与结果 | 未验证项 -->
- 2026-09-25 | 第十一批：`chatWallpaperPreset`/`customWallpaperUri`/`chatFontScale` 三个外观偏好状态收进 `ChatDetailAppearanceStates.kt`（新 `ChatDetailAppearanceState` + `refreshFrom(context)`），Route 2721→2711 行，热点上限两处同步 2721→2711 | **本轮按指示未跑 Gradle/SDK**，只做静态自检：`rg` 确认三个旧名在 chatdetail 目录零残留（`AuthApiModels/PreferencesModels/...` 里的同名字段是网络/偏好模型属性，与本改动无关，未动）；`rg -c mutableStateOf ChatDetailRoute.kt` 18→15；`wc -l` 2721→2711；新文件 import 逐项核对（Context/Composable/remember/getValue/mutableStateOf/setValue/ChatAppearancePreferences/ChatFontScale/ChatWallpaperPreset）；直连持久层命中 Route 仍为 2、新文件 0，故 U02 两张 map 未动 | **未验证：编译与测试全部未跑**（容器无 Android SDK），需本地 `:app:testDebugUnitTest` + `:app:connectedDebugAndroidTest`。刻意不带 Saver 以保持原 `remember{mutableStateOf}` 的保存语义等价，若本地跑出不一致优先怀疑这一点。
