package com.maodouchat.server.repository

/**
 * 开发者门户只读聚合（G57 从 `plugins/DeveloperRouting.kt` 的裸事务下沉）。
 *
 * 四个入口对应四个端点：命令日志分页、看板、按天分析、健康探针。
 * 全部只读 `bot_command_logs`，不触碰任何消息正文 / 密文。
 */

class DeveloperAnalyticsRepository
