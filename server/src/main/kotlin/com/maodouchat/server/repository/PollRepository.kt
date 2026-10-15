package com.maodouchat.server.repository

/**
 * 群投票子域（B3 / B05 独立子域）。
 *
 * 投票 CRUD 与投票列表同步快照、群成员名单（WS fanout）统一收敛于此。
 * 票选记录为群内公开元数据，明文存储。
 */
object PollRepository {
}
