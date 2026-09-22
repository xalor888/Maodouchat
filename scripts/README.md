# scripts/ 分类清单

本目录 37 个脚本，分三类。**分类的理由**：G199b/G200b 盘「无人调用资产」时发现
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
| `sync-direction-numbers.py` | 按实测改写 `DIRECTION.md` §0 数字；`--check` / `--write` |
| `finish-round.sh` | 一条命令收尾一整轮：全量测试 → 追加台账 → 同步 → 门禁 → 提交 |
| `qa-ui.py` | 真机 UI 检查（`dump`/`shot`/`tap`/`text`/`log`/`start`），详见 `DIRECTION.md` §4.5 |
| `use-jdk21.sh` | 把 `JAVA_HOME` 钉到 JDK 21（系统 JDK 25 会让 Kotlin DSL 崩）——`source` 使用 |
| `webrtc-sync-native.sh` | 把 WebRTC 原生 `.so` 同步到服务端 resources，供客户端按需下载 |
| `admin-e2e.mjs` / `developer-e2e.mjs` / `website-e2e.mjs` / `preview-server.mjs` | Node 侧 E2E / 预览服务 |

## 4. 一次性历史脚本（**不是工具，不要复用**）

这些是过去某次具体重构的产物：硬编码了当时的路径/行号，今天跑不了也没有跑的意義。
保留只为台账能追溯；**需要类似能力时请重写，不要改这里**。

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

> 上一节共 14 个。它们**没有任何 CI/构建/源码引用**（唯一提到它们的地方是
> `docs/full-project-refactor-checklist.md` 的历史条目）。
