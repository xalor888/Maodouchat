package com.maodouchat.data

import com.maodouchat.data.local.DatabaseMigrations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room 迁移链锁定测试（A03/Q02）。
 *
 * 仿服务端 `RouteRegistrySplitTest` 的端点数锁定：迁移链必须连续无断点，
 * 且最新版本必须与 `AppDatabase` 声明一致。升版时同步更新
 * [EXPECTED_LATEST_VERSION]（提醒补迁移 SQL + schema 导出核对）。
 */
class DatabaseMigrationsChainTest {

    @Test
    fun chainIsContinuousWithoutGaps() {
        val all = DatabaseMigrations.ALL
        assertTrue(all.isNotEmpty())
        val sorted = all.sortedBy { it.startVersion }
        for (i in sorted.indices) {
            assertEquals(
                sorted.first().startVersion + i,
                sorted[i].startVersion,
                "migration chain has a gap at index $i",
            )
            assertEquals(
                sorted[i].startVersion + 1,
                sorted[i].endVersion,
                "migration must advance exactly one version",
            )
        }
    }

    @Test
    fun latestVersionMatchesDatabase() {
        val latest = DatabaseMigrations.ALL.maxOf { it.endVersion }
        assertEquals(
            EXPECTED_LATEST_VERSION,
            latest,
            "add MIGRATION_x_y + bump AppDatabase.version + update this constant together",
        )
    }

    @Test
    fun noDuplicateEdges() {
        val edges = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(edges.size, edges.toSet().size, "duplicate migration edge")
    }

    private companion object {
        // 与 AppDatabase.version 同步（当前 40：notification_center_items）。
        const val EXPECTED_LATEST_VERSION = 40
    }
}
