# E2EE 多设备验证清单

**用途**：Signal 系端到端加密在多设备场景下的真机验证。
**关联**：`app/.../crypto/`、`app/.../watermark/`、`docs/release-checklist.md`。

> **代码完整 ≠ 真机已验**。以下每项需在双设备/多设备真机上实际操作并勾选。

---

## 1. 单聊文本 E2EE

| # | 场景 | 通过 |
|---|------|------|
| 1 | A 发文本 -> B 收到解密一致 | [ ] |
| 2 | A 多设备：手机发 -> 平板收到 | [ ] |
| 3 | B 多设备：A 发 -> B 手机+平板都解密 | [ ] |
| 4 | 离线消息：A 发 -> B 离线 -> B 上线收到 | [ ] |
| 5 | signal-v2 envelope 兼容 signal-v1 旧消息 | [ ] |

## 2. 群聊 Sender Key

| # | 场景 | 通过 |
|---|------|------|
| 1 | 3 人群：A 发 -> B/C 都解密 | [ ] |
| 2 | 加成员 D -> D 能解密新消息，旧消息不可解 | [ ] |
| 3 | 移除成员 D -> D 不再收到新消息 | [ ] |
| 4 | member_revision 变更 -> 本地 Sender Key 失效重分发 | [ ] |
| 5 | SK_DIST 逐设备加密 fanout | [ ] |
| 6 | 分发失败 -> WorkManager 后台重试 | [ ] |
| 7 | 群详情页密钥状态显示正确 | [ ] |

## 3. 身份信任 / 安全码

| # | 场景 | 通过 |
|---|------|------|
| 1 | 首次看到对方身份 -> TOFU 信任 | [ ] |
| 2 | 对方身份变化 -> 阻止解密 + 安全码变化提示 | [ ] |
| 3 | 安全码 QR 扫码核验 -> 标记信任 | [ ] |
| 4 | 设备/账号错配 -> 文案提示 | [ ] |
| 5 | 多设备安全码逐设备指纹展示 | [ ] |

## 4. 加密附件

| # | 场景 | 通过 |
|---|------|------|
| 1 | 图片：A 发 -> B 收到解密显示 | [ ] |
| 2 | 视频：A 发 -> B 收到解密播放 | [ ] |
| 3 | 文件：A 发 -> B 收到解密下载 | [ ] |
| 4 | Sender Key envelope 携带内容密钥 + IV + 哈希 + 元数据 | [ ] |
| 5 | 服务端只存密文，无法解密 | [ ] |

## 5. 密聊 / 盲水印

| # | 场景 | 通过 |
|---|------|------|
| 1 | 密聊开关 -> FLAG_SECURE 强制 | [ ] |
| 2 | 密聊图片 -> 频域盲水印注入 | [ ] |
| 3 | 截图（绕过 FLAG_SECURE）-> 取证页提取载荷 hex | [ ] |
| 4 | 水印不可见（PSNR > 38 dB） | [ ] |
| 5 | 抗 JPEG q50 / 噪声 ±8 / 75% 裁剪 | [ ] |

## 6. 设备管理

| # | 场景 | 通过 |
|---|------|------|
| 1 | 新设备登录 -> 待确认状态 | [ ] |
| 2 | 批准/拒绝待确认设备 | [ ] |
| 3 | 移除非当前设备 | [ ] |
| 4 | 设备命名 | [ ] |
| 5 | 逐设备 PreKey bundle 拉取 | [ ] |

## 7. 换号 / 登出

| # | 场景 | 通过 |
|---|------|------|
| 1 | 登出 -> 撤销当前 refresh/access token | [ ] |
| 2 | 全部退出 -> 撤销所有 refresh + 滚动 token 版本 | [ ] |
| 3 | 本地 SQLCipher + Signal 状态 + 缓存销毁 | [ ] |
| 4 | 注销账号 -> 匿名化 + 撤销会话 + 清理密钥 | [ ] |

## 8. 已知限制

- ~~GIF/语音仍使用 inline Base64 E2EE~~ -> **已修正**：GIF/VOICE 已确认使用加密附件管道（AES-256-GCM），非 inline Base64
- 安全码为自研格式（5 位分组 digest），多端必须同算法
- Sealed Sender 未实现，服务端可见会话元数据
- 后量子 Kyber 接口存在但会话未使用

---

## 9. 代码级验证证据（自动化测试）

以下项已通过自动化单测验证，提供代码级证据（真机验证仍需执行）：

| 验证项 | 测试 | 结果 |
|--------|------|------|
| DCT 往返精度 | `FrequencyWatermarkTest` | ✅ PASS |
| 载荷确定性与可区分 | `FrequencyWatermarkTest` | ✅ PASS |
| 嵌入->提取恢复载荷 | `FrequencyWatermarkTest` | ✅ PASS |
| PSNR > 38 dB（不可见性） | `FrequencyWatermarkTest` | ✅ PASS (Q=32, ~39 dB) |
| 抗加性噪声 ±8 | `FrequencyWatermarkTest` | ✅ PASS |
| 抗中心裁剪 75% | `FrequencyWatermarkTest` | ✅ PASS (2D 同步字搜索) |
| 抗 JPEG q75 | `FrequencyWatermarkTest` | ✅ PASS |
| 抗 JPEG q50 | `FrequencyWatermarkTest` | ✅ PASS (base≤16 位置) |
| 空图返回 null | `FrequencyWatermarkTest` | ✅ PASS |
| 过小图像原样返回 | `FrequencyWatermarkTest` | ✅ PASS |
| PreKey 批量校验（min 10 + keyId 范围 + 签名长度） | `PreKeyUploadValidationTest` | ✅ 10/10 PASS |
| 服务器健康检查（DB + 存储就绪） | 本地服务器集成测试 | ✅ `{"database":"ok","storage":"ok"}` |
| 用户注册 + JWT 签发 | 本地服务器集成测试 | ✅ 返回 token + refreshToken |
| Docker Compose 配置有效性 | `docker compose config` | ✅ 有效 |
