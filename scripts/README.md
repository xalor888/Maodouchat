# scripts/ 分类清单

本目录 35 个脚本（不含本文件），分四类。**分类的理由**：G199b/G200b 盘「无人调用资产」时发现
14 个一次性重构脚本和 4 个真工具混在一起——`scripts/` 是「想复用工具时第一个看的地方」，
不分类就等于把噪音和工具摆同一个抽屉。

## 1. CI 门禁（`.github/workflows/ci.yml` 调用）

| 脚本 | 职责 |
|---|---|
| `check-brand-terminology.py` | 产品用语（品牌/术语）闸门 |
| `check-nav-registration.py` | 导航路由注册闸门（P08） |
| `check-app-update-gates.py` | App 更新发版闸门（P09） |
| `check-string-parity.py` | `values/` 与 `values-en/` 字符串名 parity（G199b 从「无人调用」状态接回） |
| `two-device-http-e2e.sh` | 双设备真 HTTP E2E |
| `rehearse-pg-restore.sh` | PG 恢复演练 |

## 2. 生产运维（凭据相关，CI 不跑）

| 脚本 | 职责 |
|---|---|
| `status.sh` | 一条命令查看生产部署状态（服务/健康/版本/备份），**只读** |
| `verify-production-topology.sh` | 生产拓扑静态检查（`--live` 才做真实探测） |
| `backup-production.sh` / `restore-production.sh` | 生产库备份/恢复（CI 只做 `bash -n` 语法检查） |
| `deploy.sh` / `update.sh` | 部署/更新（需线上凭据） |
| `deploy.ps1` | Windows 侧部署入口 |

## 3. 本地开发工具

| 脚本 | 职责 |
|---|---|
| `qa-ui.py` | 真机 UI 检查（`dump`/`shot`/`tap`/`text`/`log`/`start`） |
| `use-jdk21.sh` | 把 `JAVA_HOME` 钉到 JDK 21（系统 JDK 25 会让 Kotlin DSL 崩）——`source` 使用 |
| `webrtc-sync-native.sh` | 把 WebRTC 原生 `.so` 同步到服务端 resources，供客户端按需下载 |
| `admin-e2e.mjs` / `developer-e2e.mjs` / `website-e2e.mjs` / `preview-server.mjs` | Node 侧 E2E / 预览服务 |

## 4. 一次性历史脚本（**不是工具，不要复用**）

这些是过去某次具体重构的产物：硬编码了当时的路径/行号，今天跑不了也没有跑的意義。
保留只为追溯；**需要类似能力时请重写，不要改这里**。

| 脚本 | 当时的用途 |
|---|---|
| `_bracecheck.py` | 花括号配平检查 |
| `_gp_esc.py` | 给 `GroupPlayPolicy` 注入转义 helper。**含硬编码 `D:\Maodouchat\` 路径，已失效** |
| `_gp_extract.py` | 从 `ChatDetailViewModel` 抽 GroupPlay 块（硬编码行号 5150–6871） |
| `final_cdvm.py` / `fix_final.py` | ChatDetailViewModel 收尾改写 |
| `fix_batch2.py` / `fix_batch3.py` | 批量修复 |
| `fix_chatdetail.py` | ChatDetail 编译修复 |
| `fix_compile.py` | 编译错误修复 |
| `fix_screen_calls.py` | screen 调用点修复 |
| `fix_settings.py` | settings 修复 |
| `shrink_launcher_foreground.py` | launcher foreground 收缩 |
| `split_api_models.py` | 拆分 api models |
| `write_chatdetail.py` | 写回 ChatDetail |
| `run_final_tests.bat` | Windows 侧最终测试（已无引用） |

> 上一节共 15 个文件。它们**没有任何 CI/构建/源码引用**——只作为历史留存。

## 5. Git pre-push 闸门（每个新 clone 需一次性启用）

`.githooks/pre-push` 在推送前跑 `:app:lintDebug`，失败即**拒绝推送**。

**新 clone 只需一次**（`core.hooksPath` 是本地 git 配置，不随仓库传播）：

```bash
git config core.hooksPath .githooks
```

### 为什么只跑 lintDebug

G325c 推送后 CI 四个作业里**只有 `lintDebug` 红**
（`ConfigRobustnessTest.kt:69: Constructing a view model in a composable`）。
本地一向只跑 `testDebugUnitTest`/`server test`/`connectedDebugAndroidTest`，
**lint 是从不本地跑的那一门**，于是整整一轮 CI（约 20 分钟）才暴露。
这个 hook 的职责就是「让 CI 会拒的东西推不出去」，lint 正是反复漏的一门。

代价：**冷缓存约 3 分钟，热缓存约 1 秒**（Gradle 增量判定，实测）。
这是有意的交换——3 分钟本地 vs 20 分钟 CI 红 + 重推。
确需跳过时 `git push --no-verify`，但代价自负：lint 若真有问题仍是 CI 红一轮。

> 2026-09-24：本 hook 原先还有第一步「`DIRECTION.md` 第 0 节与实测一致」
> （`scripts/sync-direction-numbers.py --check-fast`）。`DIRECTION.md` 及其新鲜度
> 门禁（`DirectionDocFreshnessTest`、该脚本、`finish-round.sh`）已整体移除，
> 第一步随之取消。
