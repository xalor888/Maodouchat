package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G146：隐私开关一族（G88 从 `GroupPlayPolicy.kt` 抽出）的往返一致性测试。
 *
 * `GroupPlaySealPolicy` 是 13 对 `formatX(mode, hostLabel)` / `parseX(content)`：
 * format 把「模式 + 宿主标签」编码成一条可显示的字符串，parse 再从字符串里取回模式。
 * 两端用 `${PREFIX}${esc(mode)}|${hostLabel} ...` 的同一形状，所以**每对都必须往返一致**。
 *
 * 这里最容易坏的有三处：
 * 1. `esc`/`unesc` 不对称——`|` 和 `^` 是负载分隔符，不转义就会把 mode 切断；
 * 2. 前缀拼错/改错——parse 直接返回 null，功能静默失效；
 * 3. `substringBefore('|')` 的边界——hostLabel 里含 `|` 不能影响取回 mode。
 */
class GroupPlaySealPolicyTest {

    private val pairs: List<Triple<String, (String, String) -> String, (String) -> String?>> = listOf(
        Triple("LINK_LOCK", GroupPlaySealPolicy::formatLinkLock, GroupPlaySealPolicy::parseLinkLock),
        Triple("PREVIEW_MUTE", GroupPlaySealPolicy::formatPreviewMute, GroupPlaySealPolicy::parsePreviewMute),
        Triple("URL_FENCE", GroupPlaySealPolicy::formatUrlFence, GroupPlaySealPolicy::parseUrlFence),
        Triple("NOTIF_MASK", GroupPlaySealPolicy::formatNotifMask, GroupPlaySealPolicy::parseNotifMask),
        Triple("LIST_BLUR", GroupPlaySealPolicy::formatListBlur, GroupPlaySealPolicy::parseListBlur),
        Triple("TRAY_SEAL", GroupPlaySealPolicy::formatTraySeal, GroupPlaySealPolicy::parseTraySeal),
        Triple("REACT_LOCK", GroupPlaySealPolicy::formatReactLock, GroupPlaySealPolicy::parseReactLock),
        Triple("STAR_SEAL", GroupPlaySealPolicy::formatStarSeal, GroupPlaySealPolicy::parseStarSeal),
        Triple("META_FENCE", GroupPlaySealPolicy::formatMetaFence, GroupPlaySealPolicy::parseMetaFence),
        Triple("TYPING_SEAL", GroupPlaySealPolicy::formatTypingSeal, GroupPlaySealPolicy::parseTypingSeal),
        Triple("READ_SEAL", GroupPlaySealPolicy::formatReadSeal, GroupPlaySealPolicy::parseReadSeal),
        Triple("PRESENCE_SEAL", GroupPlaySealPolicy::formatPresenceSeal, GroupPlaySealPolicy::parsePresenceSeal),
        Triple("LASTSEEN_SEAL", GroupPlaySealPolicy::formatLastSeenSeal, GroupPlaySealPolicy::parseLastSeenSeal),
    )

    @Test
    fun `every pair round-trips a plain mode`() {
        pairs.forEach { (name, format, parse) ->
            assertEquals("$name 往返失败", "ON", parse(format("ON", "host")))
            assertEquals("$name 往返失败", "OFF", parse(format("OFF", "host")))
            assertEquals("$name 往返失败", "AUTO", parse(format("AUTO", "host")))
        }
    }

    @Test
    fun `mode containing the separators still round-trips`() {
        // `|` 与 `^` 是负载分隔符，必须被 esc/unesc 转义后才能安全往返
        pairs.forEach { (name, format, parse) ->
            assertEquals("$name 含 | 往返失败", "A|B", parse(format("A|B", "host")))
            assertEquals("$name 含 ^ 往返失败", "A^B", parse(format("A^B", "host")))
            assertEquals("$name 同时含两者失败", "A|B^C", parse(format("A|B^C", "host")))
        }
    }

    @Test
    fun `host label containing a separator does not corrupt the mode`() {
        // substringBefore('|') 只取第一段，所以 hostLabel 里的 `|` 不该影响 mode
        pairs.forEach { (name, format, parse) ->
            assertEquals("$name 受 hostLabel 影响", "ON", parse(format("ON", "a|b^c")))
        }
    }

    @Test
    fun `blank mode parses back to null`() {
        pairs.forEach { (name, format, parse) ->
            assertNull("$name 空 mode 应解析为 null", parse(format("", "host")))
            assertNull("$name 全空白 mode 应解析为 null", parse(format("   ", "host")))
        }
    }

    @Test
    fun `mode is trimmed and truncated to forty chars`() {
        pairs.forEach { (name, format, parse) ->
            assertEquals("$name 未 trim", "ON", parse(format("  ON  ", "host")))
            // take(40)：41 个字符会被截断，所以往返拿回的是前 40 个
            val long = "x".repeat(41)
            assertEquals("$name 截断长度不对", "x".repeat(40), parse(format(long, "host")))
            val exact = "y".repeat(40)
            assertEquals("$name 40 字符不应被截断", exact, parse(format(exact, "host")))
        }
    }

    @Test
    fun `wrong prefix parses to null`() {
        pairs.forEach { (name, _, parse) ->
            assertNull("$name 不应解析别的前缀", parse("SOMETHINGELSE:ON|host"))
            assertNull("$name 不应解析空前缀", parse("ON|host"))
            assertNull("$name 不应解析空字符串", parse(""))
        }
    }

    @Test
    fun `each pair only accepts its own prefix`() {
        // 交叉验证：用 A 的 format 产出的字符串，B 的 parse 必须返回 null
        pairs.forEach { (nameA, formatA, _) ->
            val payload = formatA("ON", "host")
            pairs.forEach { (nameB, _, parseB) ->
                if (nameA == nameB) return@forEach
                assertNull("$nameB 误解析了 $nameA 的负载", parseB(payload))
            }
        }
    }

    @Test
    fun `prefixes are the stable wire-format constants`() {
        // 前缀是协议常量（对端按它识别这条负载），拼错一个字母虽然往返仍自洽，
        // 但会对端解析失败——所以把 13 个字面值逐个钉住。
        assertEquals("LINKLOCK:", GroupPlaySealPolicy.LINK_LOCK_PREFIX)
        assertEquals("PREVIEWMUTE:", GroupPlaySealPolicy.PREVIEW_MUTE_PREFIX)
        assertEquals("URLFENCE:", GroupPlaySealPolicy.URL_FENCE_PREFIX)
        assertEquals("NOTIFMASK:", GroupPlaySealPolicy.NOTIF_MASK_PREFIX)
        assertEquals("LISTBLUR:", GroupPlaySealPolicy.LIST_BLUR_PREFIX)
        assertEquals("TRAYSEAL:", GroupPlaySealPolicy.TRAY_SEAL_PREFIX)
        assertEquals("REACTLOCK:", GroupPlaySealPolicy.REACT_LOCK_PREFIX)
        assertEquals("STARSEAL:", GroupPlaySealPolicy.STAR_SEAL_PREFIX)
        assertEquals("METAFENCE:", GroupPlaySealPolicy.META_FENCE_PREFIX)
        assertEquals("TYPINGSEAL:", GroupPlaySealPolicy.TYPING_SEAL_PREFIX)
        assertEquals("READSEAL:", GroupPlaySealPolicy.READ_SEAL_PREFIX)
        assertEquals("PRESENCESEAL:", GroupPlaySealPolicy.PRESENCE_SEAL_PREFIX)
        assertEquals("LASTSEENSEAL:", GroupPlaySealPolicy.LASTSEEN_SEAL_PREFIX)
    }

    @Test
    fun `formatted payload keeps the documented shape`() {
        // `${PREFIX}${esc(mode)}|${hostLabel} <kind>`
        assertEquals("LINKLOCK:ON|myhost link lock", GroupPlaySealPolicy.formatLinkLock("ON", "myhost"))
        assertEquals("READSEAL:OFF|myhost read seal", GroupPlaySealPolicy.formatReadSeal("OFF", "myhost"))
        assertEquals(
            "LASTSEENSEAL:A\u0001B|myhost last seen seal",
            GroupPlaySealPolicy.formatLastSeenSeal("A|B", "myhost")
        )
    }

    @Test
    fun `parse tolerates a payload without the separator`() {
        assertEquals("ON", GroupPlaySealPolicy.parseLinkLock("LINKLOCK:ON"))
        assertEquals("OFF", GroupPlaySealPolicy.parseReadSeal("READSEAL:OFF"))
    }

    @Test
    fun `host label is kept verbatim including spaces`() {
        assertEquals(
            "LINKLOCK:ON|  host   link lock",
            GroupPlaySealPolicy.formatLinkLock("ON", "  host  ")
        )
        assertEquals(
            "PREVIEWMUTE:ON| preview mute",
            GroupPlaySealPolicy.formatPreviewMute("ON", "")
        )
    }

    @Test
    fun `separator at the forty char edge survives or is cut with the mode`() {
        val edge = "a".repeat(39) + "|"
        assertEquals(edge, GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock(edge, "host")))
        val over = "b".repeat(40) + "|"
        assertEquals("b".repeat(40), GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock(over, "host")))
    }

    @Test
    fun `prefix matching is case sensitive and exact`() {
        assertNull(GroupPlaySealPolicy.parseLinkLock("linklock:ON|host"))
        assertNull(GroupPlaySealPolicy.parseLinkLock("LINKLOCKX:ON|host"))
        assertNull(GroupPlaySealPolicy.parseLinkLock("LINKLOCK :ON|host"))
    }

    @Test
    fun `parse takes text before the first pipe regardless of trailing pipes`() {
        assertEquals("ON", GroupPlaySealPolicy.parseLinkLock("LINKLOCK:ON|a|b"))
        assertEquals("ON", GroupPlaySealPolicy.parseLinkLock("LINKLOCK:ON|host link lock extra"))
        assertEquals("ON", GroupPlaySealPolicy.parseLinkLock("LINKLOCK:ON|"))
    }

    // ---- oct22ju 续批：五态 ----

    @Test
    fun `bare prefix or prefix plus pipe parses to null for every pair`() {
        pairs.forEach { (name, _, parse) ->
            val prefix = when (name) {
                "LINK_LOCK" -> GroupPlaySealPolicy.LINK_LOCK_PREFIX
                "PREVIEW_MUTE" -> GroupPlaySealPolicy.PREVIEW_MUTE_PREFIX
                "URL_FENCE" -> GroupPlaySealPolicy.URL_FENCE_PREFIX
                "NOTIF_MASK" -> GroupPlaySealPolicy.NOTIF_MASK_PREFIX
                "LIST_BLUR" -> GroupPlaySealPolicy.LIST_BLUR_PREFIX
                "TRAY_SEAL" -> GroupPlaySealPolicy.TRAY_SEAL_PREFIX
                "REACT_LOCK" -> GroupPlaySealPolicy.REACT_LOCK_PREFIX
                "STAR_SEAL" -> GroupPlaySealPolicy.STAR_SEAL_PREFIX
                "META_FENCE" -> GroupPlaySealPolicy.META_FENCE_PREFIX
                "TYPING_SEAL" -> GroupPlaySealPolicy.TYPING_SEAL_PREFIX
                "READ_SEAL" -> GroupPlaySealPolicy.READ_SEAL_PREFIX
                "PRESENCE_SEAL" -> GroupPlaySealPolicy.PRESENCE_SEAL_PREFIX
                else -> GroupPlaySealPolicy.LASTSEEN_SEAL_PREFIX
            }
            assertNull("$name 裸前缀应为 null", parse(prefix))
            assertNull("$name 前缀加分隔符应为 null", parse(prefix + "|host"))
        }
    }

    @Test
    fun `mode with newline or tab round-trips for every pair`() {
        pairs.forEach { (name, format, parse) ->
            assertEquals("$name 换行往返失败", "A\nB", parse(format("A\nB", "host")))
            assertEquals("$name 制表符往返失败", "A\tB", parse(format("A\tB", "host")))
        }
    }

    @Test
    fun `host label mimicking the kind suffix does not corrupt the mode`() {
        assertEquals(
            "ON",
            GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock("ON", "host link lock"))
        )
        assertEquals(
            "OFF",
            GroupPlaySealPolicy.parseReadSeal(GroupPlaySealPolicy.formatReadSeal("OFF", "host read seal"))
        )
    }

    @Test
    fun `every pair format output ends with its documented kind phrase`() {
        val kinds = mapOf(
            GroupPlaySealPolicy::formatLinkLock to "link lock",
            GroupPlaySealPolicy::formatPreviewMute to "preview mute",
            GroupPlaySealPolicy::formatUrlFence to "url fence",
            GroupPlaySealPolicy::formatNotifMask to "notif mask",
            GroupPlaySealPolicy::formatListBlur to "list blur",
            GroupPlaySealPolicy::formatTraySeal to "tray seal",
            GroupPlaySealPolicy::formatReactLock to "react lock",
            GroupPlaySealPolicy::formatStarSeal to "star seal",
            GroupPlaySealPolicy::formatMetaFence to "meta fence",
            GroupPlaySealPolicy::formatTypingSeal to "typing seal",
            GroupPlaySealPolicy::formatReadSeal to "read seal",
            GroupPlaySealPolicy::formatPresenceSeal to "presence seal",
            GroupPlaySealPolicy::formatLastSeenSeal to "last seen seal",
        )
        kinds.forEach { (format, kind) ->
            assertTrue("缺少 kind 后缀 $kind", format("ON", "host").endsWith(kind))
        }
    }

    @Test
    fun `caret at the forty char edge survives or is cut with the mode`() {
        val edge = "c".repeat(39) + "^"
        assertEquals(edge, GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock(edge, "host")))
        val over = "d".repeat(40) + "^"
        assertEquals("d".repeat(40), GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock(over, "host")))
    }

}
