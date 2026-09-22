package com.maodouchat

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G205b：**账号隔离**的 DAO 闸门。
 *
 * 背景：`AccountFeatureSwitch` 的账号隔离我立过闸门（G184b），但**数据库这边没有**。
 * `data/local/dao/` 下 263 条 `@Query`，其中触及「有 `ownerUserId` 列的表」的约 130 条。
 * 这个仓是本地多账号的（切号不删库），所以**漏一条 `ownerUserId` 谓词就等于
 * 账号 A 能读到/删掉账号 B 的定时消息、附件传输、草稿、AI 结果**。
 *
 * 做法：源码文本判据（按 DIRECTION.md §3.5 **先剥注释再比**）——
 * 每条触及 owner 表的 `@Query` 必须含 `ownerUserId = :ownerUserId`；
 * 例外放进一张**显式豁免表**（附理由），宁可显式豁免也不静默放过。
 */
class DaoOwnerScopingTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "找不到仓库根（从 ${File("").absoluteFile} 向上）" }
    }

    /** 与其它门禁同一份实现：剥块注释/行注释，保留字符串字面量。 */
    private fun codeOnly(raw: String): String {
        val out = StringBuilder()
        var i = 0
        var lineStart = true
        while (i < raw.length) {
            val c = raw[i]
            if (lineStart && raw.startsWith("//", i)) {
                while (i < raw.length && raw[i] != '\n') i++
                continue
            }
            if (raw.startsWith("/*", i)) {
                val end = raw.indexOf("*/", i + 2)
                i = if (end < 0) raw.length else end + 2
                continue
            }
            if (c == '"') {
                val end = raw.indexOf('"', i + 1)
                out.append(raw, i, if (end < 0) raw.length else end + 1)
                i = if (end < 0) raw.length else end + 1
                lineStart = false
                continue
            }
            out.append(if (c == '\n') '\n' else c)
            lineStart = (c == '\n')
            i++
        }
        return out.toString()
    }

    private fun norm(sql: String): String =
        sql.replace("\\\"", "\"").replace(Regex("\\s+"), "").lowercase()

    /** 显式豁免：这些查询故意不加 `ownerUserId = :ownerUserId`，理由写明。 */
    private val exemptions: List<Pair<String, String>> = listOf(
        // —— 登出/全局清理：WorkManager 或账号迁移触发，本就要跨账号 ——
        "deletefromattachment_transfers" to "上传失败兜底的全局清理",
        "deletefromchat_drafts" to "登出清理全部草稿",
        "deletefromsender_key_retry_queue" to "登出清理重试队列",
        "deletefromai_operations" to "登出清理 AI 操作",
        // —— owner 解析：只取 ownerUserId 本身或按 id 定位，敏感读取随后仍是 owner 作用域 ——
        "select*fromattachment_transfersorderbycreatedatasc" to "上传队列巡检（Worker 单账号环境）",
        "selectowneruseridfromattachment_transferswheremessageid" to "只取归属，用于后续 owner 作用域操作",
        "select*fromai_operationswhereid=:operationidlimit1" to "按 id 读单个 AI 操作（调用方已校验归属）",
        // 缓存目录是**所有保留账号共享**的：清理某账号的孤儿文件时，必须先知道
        // 全部账号的在用路径，否则会删掉别的账号休眠中的文件。
        // 只取 encryptedPath / sourceUri 拼 protect-set，不读内容。
        "select*fromattachment_transfers" to "共享缓存清理的 protect-set（AttachmentTransferCoordinator + MediaCache）",
        "select*fromscheduled_messageswhereid=:idlimit1" to "Worker 恢复 expectedOwnerUserId",
        "deletefromscheduled_messageswhereid=:id" to "Worker 已解析出 owner 后删除",
        "updatesender_key_retry_queue setowneruserid".replace(" ","") to "账号迁移回填（老账号 id 为空的历史行）",
    )

    @Test
    fun everyQueryOnAnOwnerScopedTableFiltersByOwner() {
        val root = repoRoot()
        val daoDir = File(root, "app/src/main/java/com/maodouchat/data/local/dao")
        val entDir = File(root, "app/src/main/java/com/maodouchat/data/local/entity")

        // 1) 哪些表有 ownerUserId 列
        val ownerTables = mutableSetOf<String>()
        entDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = codeOnly(f.readText())
            val table = Regex("tableName\\s*=\\s*\"([a-z_]+)\"").find(text)?.groupValues?.get(1) ?: return@forEach
            if (Regex("\\bval\\s+ownerUserId\\s*:").containsMatchIn(text)) ownerTables += table
        }
        assertTrue(ownerTables.isNotEmpty(), "没识别到任何 owner 表——闸门失效")

        // 2) 逐条 @Query 检查
        val violations = mutableListOf<String>()
        var checked = 0
        daoDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = codeOnly(f.readText())
            Regex("@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)").findAll(text).forEach { m ->
                val sql = m.groupValues[1]
                val tables = Regex("\\b(?:from|into|update)\\s+([a-z_]+)", RegexOption.IGNORE_CASE)
                    .findAll(sql).map { it.groupValues[1].lowercase() }.toSet()
                if (tables.none { it in ownerTables }) return@forEach
                checked++
                val n = norm(sql)
                if ("owneruserid=:owneruserid" in n) return@forEach
                if (exemptions.any { (prefix, _) -> n.startsWith(prefix) || n.contains(prefix) }) return@forEach
                violations += "[${f.name}] $sql"
            }
        }

        assertTrue(checked >= 80, "只检查到 $checked 条——多半是解析坏了，闸门失效")
        assertEquals(
            emptyList(), violations,
            "这些 @Query 触及有 ownerUserId 列的表，却没有 ownerUserId = :ownerUserId 谓词。" +
                "要么补上谓词，要么加进显式豁免表并写明理由。",
        )
    }

    @Test
    fun exemptionListHasNoStaleEntries() {
        // 豁免表是为了「显式」而不是「免维护」：每条都必须仍能对上真实 SQL，
        // 否则说明查询被删了而豁免还挂着（会把未来的违规一起放过去）。
        val root = repoRoot()
        val daoDir = File(root, "app/src/main/java/com/maodouchat/data/local/dao")
        val allSql = daoDir.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> codeOnly(f.readText()).let { t ->
                Regex("@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)").findAll(t).map { it.groupValues[1] }.toList()
            } }
            .map(::norm)
            .toList()

        val stale = exemptions.filter { (prefix, _) ->
            allSql.none { it.startsWith(prefix) || it.contains(prefix) }
        }
        assertEquals(emptyList(), stale.map { it.first }, "豁免表里有对不上任何 SQL 的过期条目")
    }
}
