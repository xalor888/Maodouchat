# 应用更新：发布渠道、签名与回滚

本文锁定 P09 / B14 的发布语义。客户端只认官方 HTTPS 清单 + 不可变制品元数据；不走 GitHub 直链安装。

## 1. 发布渠道

| 渠道 | 谁写入 | 客户端如何消费 |
|------|--------|----------------|
| 官服 `chat.mdou.me` / `*.mdou.me` | 运维经带 token 的发布 API 上传 APK | `AppUpdatePolicy.isOfficialApkUrl` 白名单 |
| 自建服 | 该服管理员上传到本机 `/api/public/app-update/` | APK host 必须等于当前 `ApiConfig.BASE_URL` host |
| GitHub / 第三方商店直链 | **禁止**作为应用内更新源 | `shouldOfferUpdate` 直接拒绝非 HTTPS 官方/自建 URL |

清单字段：`versionCode`、`versionName`、`apkUrl`、`apkSha256`、可选 `notes`。SHA-256 必须是 64 位十六进制。

## 2. 签名证书信任

安装前（`OfficialApkInstaller` + `AppUpdateInstallPolicy`）同时校验：

1. **包名**：APK 包名 == 已安装包名。
2. **签名证书摘要集合**：archive signer digests == installed signer digests（集合相等，不允许子集/超集偷换）。
3. **archive versionCode**：等于清单 `versionCode`，且严格大于已安装 `versionCode`（禁止降级与错包替换）。

Android P+ 使用 `signingInfo` 提取证书；摘要十六进制小写规范化后再比较。

## 3. 服务端不可变制品

发布成功后：

- APK 字节写入固定路径；旁路写入 `versionCode/versionName/apkSha256/bytes` manifest。
- manifest 带 HMAC-SHA256（key = `JWT_SECRET`），公开接口优先读 manifest，避免运行时配置被改写后静默降级。
- `AppUpdatePublishPolicy.isDowngrade`：新 `versionCode` 必须严格大于当前已发布版本；相等或更小拒绝。

## 4. 下载与失败语义

- 唯一 WorkManager 任务（`AppUpdateDownloadScheduler`）：联网约束 + 指数退避。
- Worker 内：下载 → SHA → 包名/签名 → archive versionCode → 弹安装器。
- **可重试**：HTTP/IO 失败（`AppUpdateDownloadRetryPolicy`）。
- **不可重试**：完整性/签名/包名/versionCode 失败（避免毒包循环）。

## 5. 回滚策略

| 场景 | 做法 |
|------|------|
| 新版已发布但未大规模安装 | 停止对外清单（或撤回运行时配置），**不要**用更低 versionCode 覆盖；客户端拒绝降级 |
| 新版已安装出现严重缺陷 | 发布 **更高** versionCode 的修复包；必要时配合服务端强制最低版本门禁 |
| 错误签名/错包上传 | 发布 API 拒绝或 Worker 拒绝安装；删除错误制品后重新发布更高 versionCode |
| 自建服切服 | URL/凭据原子切换；旧服 APK 不再被 `isOfficialApkUrl` 接受 |

回滚演练检查表：

1. 本地安装版 A，发布版 B（B>A），确认能下载安装。
2. 尝试发布版 A'（A'≤B）：服务端拒绝。
3. 篡改 SHA / 换签名 APK：Worker 失败且不重试。
4. 进程杀死于下载中：WorkManager 恢复后仍校验完整性。

## 6. 自动检查

仓库根目录执行：

```bash
python3 scripts/check-app-update-gates.py
```

该脚本静态确认客户端/服务端更新门禁与单测文件仍存在，并接入 CI android 作业。

## 7. 生产不可达时的人工恢复（G331c）

**背景事实（G331c 逐条查实，不是推测）**：`release.yml` 的 `release` job 中，
`Publish GitHub Release` **先于** `Upload APK to chat server` 执行。
所以上传失败时**制品一定已经在 GitHub Release 上**——这是恢复的物资基础。

而 `Upload APK to chat server` 是用户拿到更新的**唯一**通道
（GitHub 直链被禁为更新源，见本文档上半部分）。
v1.3.0 就是这样卡住的：curl 连不上 `chat.mdou.me:443`（退出 28），
制品在 Release 上，但用户拿不到。

### 恢复路径 A：重跑工作流（推荐）

```bash
gh run rerun <run id> --failed
```

之所以可靠：`Package release assets + checksums` 之后有一步
`Stash release assets as an artifact (for recovery)`（`actions/upload-artifact@v4`，
名为 `release-assets-<版本>`），而 `Upload APK to chat server` 之前有
`Restore release assets from artifact`（`actions/download-artifact@v4`）。
所以无论 `--failed` 的语义是「整 job 重跑」还是「只重跑失败步骤、复用成功步骤产物」，
APK 都在手边。**不必重新打 tag。**

### 恢复路径 B：完全手动

```bash
# 1. 从 Release 取回制品（不重新构建）
gh release download v<版本> --pattern '*.apk' --dir /tmp/maodou-apk

# 2. 上传到生产（用 release.yml 里同样的三个头）
curl --fail-with-body -sS -X PUT "$UPDATE_SERVER_URL/api/internal/app-update" \
  -H "Authorization: Bearer $UPDATE_DEPLOY_TOKEN" \
  -H "X-Version-Code: <versionCode>" \
  -H "X-Version-Name: <版本>" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @/tmp/maodou-apk/<apk 文件名>
```

成功后响应形如 `{"ok":true,"versionCode":...}`（参考 1.2.1 那次：
`{"ok":true,"versionCode":912,"versionName":"1.2.1","bytes":13275173}`）。

### ⚠️ 一个仍未消除的盲区（G331c 记录）

**`release.yml` 没有任何 CI 作业校验。** 已实测：`ci.yml` 里没有 actionlint、
没有 YAML 校验；`.github/workflows/` 内唯一「提及」release.yml 的地方是它自己的注释。
它只在打 tag 时运行，所以**语法错误或步骤引用断裂只会在真发版时暴露**——
正是最不该出错的时刻。G331c 只做到「改动后用 `python3 -c "import yaml; yaml.safe_load(...)"`
本地解析验证 + 列出全部 18 个步骤人工核对」，**没有把 actionlint 接进 CI**
（那是构建变更，超出本轮边界）。若要把这个盲区真正堵上，
下一步是在 ci.yml 加一个 actionlint 作业——记在这里，因为它值得做。
