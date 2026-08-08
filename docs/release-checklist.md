# 发版检查清单

**用途**：每次正式发版前逐项勾选，确保质量门禁通过。
**关联**：`docs/feature-inventory.md`、各 `*-verification.md`。

---

## 1. 构建门禁

- [ ] `:app:compileDebugKotlin` BUILD SUCCESSFUL
- [ ] `:app:testDebugUnitTest` 全绿
- [ ] `cd server && ../gradlew.bat compileKotlin test` 全绿
- [ ] `docker compose --env-file .env.docker.example config` 无报错
- [ ] Release 构建显式配置 HTTPS/WSS 地址：`-PMAODOU_RELEASE_API_BASE_URL=https://... -PMAODOU_RELEASE_WS_URL=wss://...`
- [ ] `:app:assembleRelease` 成功，R8 + 资源收缩开启
- [ ] 包体 ≤ `docs/size-baseline.md` 基线 + 10%

## 2. 安全门禁

- [ ] 生产配置校验全部通过（JWT/HTTPS/DB/SMTP/TURN）
- [ ] SQLCipher 密钥由 Keystore 保护
- [ ] Signal PreKey bundle 频率限制生效
- [ ] JWT refresh token 轮换 + 吊销机制验证
- [ ] 防截屏 FLAG_SECURE 在密聊表面强制生效
- [ ] 管理后台 admin-jwt 不写 localStorage，5 分钟过期
- [ ] FCM 推送 payload 不含 E2EE 明文

## 3. E2EE 验证

- [ ] 单聊文本 signal-v2 envelope 收发解密一致（双设备）
- [ ] 群聊 Sender Key 分发 + epoch 绑定正确
- [ ] 安全码 TOFU + 变化拦截 + QR 扫码核验
- [ ] 加密附件 AES-GCM 分片上传 + Range 下载 + 哈希校验
- [ ] 密聊频域盲水印嵌入 + 提取（取证页验证）
- 参见 `docs/e2ee-multidevice-verification.md`

## 4. 通话验证

- [ ] 1:1 语音/视频通话接通、挂断、未接来电
- [ ] 群通话 mesh ≤ 6 人
- [ ] ICE/TURN 签发 + STUN 回退
- [ ] 通话前台服务（Android 14 类型）
- 参见 `docs/call-reliability-verification.md`

## 5. 附件可靠性

- [ ] 100 MB 文件分片上传 + 断点续传
- [ ] 进程重启后 Worker 恢复
- [ ] 暂停/继续/取消
- [ ] 1 GB 用户配额拦截
- [ ] 24 小时未提交清理
- 参见 `docs/attachment-reliability-verification.md`

## 6. UI / 动效

- [ ] 深浅色切换无闪烁
- [ ] 消息列表滚动 60fps（开发者选项 GPU 渲染）
- [ ] 列表项 animateItem 位移无跳变
- [ ] Coil 图片加载有占位 + 渐显
- [ ] 密聊盲水印不可见（PSNR > 38 dB）
- 参见 `docs/ui-motion-performance-budget.md`

## 7. 多语言

- [ ] `scripts/check-string-parity.py` 中英 name 对齐
- [ ] 关键页面中英文截图对比
- [ ] 复数与字符串数组正确

## 8. 运维

- [ ] 备份脚本生成 SHA-256 清单
- [ ] 恢复脚本可从备份还原
- [ ] 拓扑校验 `scripts/verify-production-topology.sh` 通过
- 参见 `docs/production-topology-acceptance.md`、`docs/backup-restore-acceptance.md`

## 9. 管理后台

- [ ] admin-jwt 二次确认登录
- [ ] 仪表盘 / 趋势 / 系统统计 正确
- [ ] 在线用户 / 排行榜 / 存储用量 新增页正常
- [ ] 用户封禁/解封/停用 审计写入
- [ ] 举报处理 + 风控规则 CRUD
- [ ] 审计日志 CSV 导出（防公式注入）

## 10. 发版后

- [ ] 更新 `docs/feature-inventory.md` 完整度标签
- [ ] 更新 `docs/size-baseline.md` 包体基线
- [ ] 版本号 + 更新日志
