# 通话可靠性验证清单

**用途**：WebRTC 音视频通话的真机验证。
**关联**：`app/.../webrtc/`、`app/.../ui/screen/call/`、`docs/release-checklist.md`。

---

## 1. 1:1 语音

| # | 场景 | 通过 |
|---|------|------|
| 1 | A 拨打 -> B 来电 -> 接通 | [ ] |
| 2 | B 拒接 -> A 收到拒接 | [ ] |
| 3 | A 取消 -> B 收到取消 | [ ] |
| 4 | 通话中静音 / 取消静音 | [ ] |
| 5 | 通话中切换听筒 / 扬声器 / 蓝牙 | [ ] |
| 6 | 通话中切换前后摄像头 | [ ] |
| 7 | 任一挂断 -> 双方结束 | [ ] |
| 8 | 未接来电 -> 记录 + 回拨 | [ ] |

## 2. 1:1 视频

| # | 场景 | 通过 |
|---|------|------|
| 1 | A 视频拨打 -> B 接通 -> 双向视频 | [ ] |
| 2 | 通话中关闭摄像头 -> 切语音 | [ ] |
| 3 | 通话中开启摄像头 -> 切视频 | [ ] |
| 4 | 横竖屏切换不中断 | [ ] |

## 3. 群通话 (mesh ≤ 6)

| # | 场景 | 通过 |
|---|------|------|
| 1 | 3 人群通话 -> 全部接通 | [ ] |
| 2 | 6 人群通话 -> 全部接通 | [ ] |
| 3 | 第 7 人 -> 超限提示 | [ ] |
| 4 | 通话中显示人数 / 上限 | [ ] |
| 5 | 成员加入 / 离开 -> mesh 更新 | [ ] |

## 4. ICE / TURN

| # | 场景 | 通过 |
|---|------|------|
| 1 | TURN 签发短期凭据 | [ ] |
| 2 | STUN 回退 -> UI 明示「仅 STUN」 | [ ] |
| 3 | ICE 连接成功（非对称 NAT 需 TURN） | [ ] |
| 4 | 连接质量胶囊 RTT/丢包显示 | [ ] |

## 5. 前台服务

| # | 场景 | 通过 |
|---|------|------|
| 1 | 通话中前台服务通知显示 | [ ] |
| 2 | Android 14 前台服务类型正确 | [ ] |
| 3 | 后台不挂断 | [ ] |
| 4 | 锁屏不挂断 | [ ] |

## 6. 推送来电

| # | 场景 | 通过 |
|---|------|------|
| 1 | App 在后台 -> FCM 来电推送 -> 唤醒接听 | [ ] |
| 2 | App 被杀 -> FCM 来电推送 -> 冷启动接听 | [ ] |
| 3 | pending 信令 -> 打开 App 后接通 | [ ] |

## 7. 弱网

| # | 场景 | 通过 |
|---|------|------|
| 1 | 200ms 延迟 -> 通话可维持 | [ ] |
| 2 | 3% 丢包 -> 通话可维持（质量下降可接受） | [ ] |
| 3 | 网络切换 WiFi <-> 4G -> ICE 重连 | [ ] |

## 8. 已知限制

- 无屏幕共享 / 录制
- 无 SFU（大群需选成员，mesh 上限 6 人）
- 无 ConnectionService 系统来电集成
- 厂商推送通道未做（仅 FCM）

---

## 9. 代码级验证证据（自动化测试）

| 验证项 | 测试 | 结果 |
|--------|------|------|
| ICE FAILED -> RESTART_ICE（首次） | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE FAILED -> RESTART_ICE（第二次） | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE FAILED -> END_NOW（超过 2 次） | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE DISCONNECTED -> START_GRACE | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE CONNECTED -> CANCEL_GRACE | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| 大小写不敏感状态匹配 | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE_MAX_RESTART_ATTEMPTS = 2 | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| ICE_RESTART_INTERVAL_MS = 5000 | `CallReliabilityPolicyIceRestartTest` | ✅ PASS |
| WebRTC GATHER_CONTINUALLY 已配置 | 代码审查 `WebRTCManager.kt` | ✅ 1:1 + 群通话 |
| restartIce() 在 DISCONNECTED/FAILED 时调用 | 代码审查 `WebRTCManager.kt` | ✅ 1:1 + 群通话 |
