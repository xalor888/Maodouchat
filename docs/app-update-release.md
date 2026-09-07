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
