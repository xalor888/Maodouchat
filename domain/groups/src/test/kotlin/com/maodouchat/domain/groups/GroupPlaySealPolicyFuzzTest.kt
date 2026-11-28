package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GroupPlaySealPolicy 13 组 format/parse fuzz（清单 931 行第十五块，簇 2）。
 *
 * 13 组形状完全一致（PREFIX + esc(mode) + "|" + hostLabel + " 开关名"），逐字核对生产文件：
 * - mode 先 trim 再 take(40)，`|`/`^` 转义为 `\u0001`/`\u0002`，解析取 `|` 前段反转义，空白返 null；
 * - mode 含字面量 `\u0001`/`\u0002` 时往返会变义（unesc 把它翻回 `|`/`^`），故随机 mode
 *   不含这两个控制字符——这是实现约定的边界，不是测试遗漏；
 * - 各组 PREFIX 均以 `:` 结尾且互不为前缀，交叉解析恒 null。
 */
class GroupPlaySealPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 150
        private val groups: List<Triple<String, (String, String) -> String, (String) -> String?>> =
            listOf(
                Triple(GroupPlaySealPolicy.LINK_LOCK_PREFIX, GroupPlaySealPolicy::formatLinkLock, GroupPlaySealPolicy::parseLinkLock),
                Triple(GroupPlaySealPolicy.PREVIEW_MUTE_PREFIX, GroupPlaySealPolicy::formatPreviewMute, GroupPlaySealPolicy::parsePreviewMute),
                Triple(GroupPlaySealPolicy.URL_FENCE_PREFIX, GroupPlaySealPolicy::formatUrlFence, GroupPlaySealPolicy::parseUrlFence),
                Triple(GroupPlaySealPolicy.NOTIF_MASK_PREFIX, GroupPlaySealPolicy::formatNotifMask, GroupPlaySealPolicy::parseNotifMask),
                Triple(GroupPlaySealPolicy.LIST_BLUR_PREFIX, GroupPlaySealPolicy::formatListBlur, GroupPlaySealPolicy::parseListBlur),
                Triple(GroupPlaySealPolicy.TRAY_SEAL_PREFIX, GroupPlaySealPolicy::formatTraySeal, GroupPlaySealPolicy::parseTraySeal),
                Triple(GroupPlaySealPolicy.REACT_LOCK_PREFIX, GroupPlaySealPolicy::formatReactLock, GroupPlaySealPolicy::parseReactLock),
                Triple(GroupPlaySealPolicy.STAR_SEAL_PREFIX, GroupPlaySealPolicy::formatStarSeal, GroupPlaySealPolicy::parseStarSeal),
                Triple(GroupPlaySealPolicy.META_FENCE_PREFIX, GroupPlaySealPolicy::formatMetaFence, GroupPlaySealPolicy::parseMetaFence),
                Triple(GroupPlaySealPolicy.TYPING_SEAL_PREFIX, GroupPlaySealPolicy::formatTypingSeal, GroupPlaySealPolicy::parseTypingSeal),
                Triple(GroupPlaySealPolicy.READ_SEAL_PREFIX, GroupPlaySealPolicy::formatReadSeal, GroupPlaySealPolicy::parseReadSeal),
                Triple(GroupPlaySealPolicy.PRESENCE_SEAL_PREFIX, GroupPlaySealPolicy::formatPresenceSeal, GroupPlaySealPolicy::parsePresenceSeal),
                Triple(GroupPlaySealPolicy.LASTSEEN_SEAL_PREFIX, GroupPlaySealPolicy::formatLastSeenSeal, GroupPlaySealPolicy::parseLastSeenSeal),
            )
        private val modeChars = listOf('a', 'b', 'x', 'y', ' ', '|', '^', '1', '-', '_')
        private val hostChars = listOf('A', 'l', 'i', 'c', 'e', ' ', '|', 'B', 'o')
    }

    private fun randomMode(random: Random): String {
        val length = random.nextInt(0, 51)
        return buildString {
            repeat(length) { append(modeChars[random.nextInt(modeChars.size)]) }
        }
    }

    private fun randomHost(random: Random): String {
        val length = random.nextInt(0, 9)
        return buildString {
            repeat(length) { append(hostChars[random.nextInt(hostChars.size)]) }
        }
    }

    @Test
    fun `allGroupsRoundTripRandomModes`() {
        val random = Random(0x15_2026)
        groups.forEach { (prefix, format, parse) ->
            repeat(RANDOM_ITERATIONS) { i ->
                val mode = randomMode(random)
                val host = randomHost(random)
                val formatted = format(mode, host)
                assertEquals(formatted, format(mode, host), "$prefix 迭代 #$i：format 必须确定")
                assertTrue(formatted.startsWith(prefix), "$prefix 迭代 #$i：format 必须以 $prefix 开头")
                val expected = mode.trim().take(40).ifBlank { null }
                assertEquals(
                    expected, parse(formatted),
                    "$prefix 迭代 #$i：往返必须等于 trim 后截 40 的 mode，mode=$mode host=$host"
                )
            }
        }
    }

    @Test
    fun `blankModeAlwaysParsesToNull`() {
        val blanks = listOf("", " ", "   ", "\t", "\n  \t ")
        groups.forEach { (prefix, format, parse) ->
            blanks.forEach { mode ->
                assertNull(parse(format(mode, "Alice")), "$prefix 空白 mode $mode 必须解析为 null")
            }
        }
        val random = Random(0x15_2026)
        groups.forEach { (prefix, format, parse) ->
            repeat(50) { i ->
                val mode = " ".repeat(random.nextInt(1, 10))
                assertNull(parse(format(mode, "Bob")), "$prefix 迭代 #$i：纯空白 mode 必须解析为 null")
            }
        }
    }

    @Test
    fun `crossPrefixParseAlwaysNull`() {
        groups.forEach { (prefix, format, _) ->
            val formatted = format("strict", "Alice")
            groups.forEach { (otherPrefix, _, otherParse) ->
                if (otherPrefix != prefix) {
                    assertNull(otherParse(formatted), "$formatted 用 $otherPrefix 解析必须为 null")
                }
            }
        }
    }

    @Test
    fun `randomGarbageParseNeverThrowsAndStaysBlankOrNull`() {
        val random = Random(0x15_2026)
        val garbageChars = listOf('a', 'b', ':', '|', '^', ' ', '\u0001', '\u0002', 'L', 'I', 'N', 'K')
        groups.forEach { (prefix, _, parse) ->
            repeat(RANDOM_ITERATIONS) { i ->
                val length = random.nextInt(0, 31)
                val garbage = buildString {
                    repeat(length) { append(garbageChars[random.nextInt(garbageChars.size)]) }
                }
                val parsed = parse(garbage)
                assertTrue(
                    parsed == null || parsed.isNotBlank(),
                    "$prefix 迭代 #$i：随机垃圾解析只能返回 null 或非空串，实际 $parsed"
                )
                // 带正确前缀的垃圾也一样：只返 null 或非空串，永不抛
                val prefixed = prefix + garbage
                val parsedPrefixed = parse(prefixed)
                assertTrue(
                    parsedPrefixed == null || parsedPrefixed.isNotBlank(),
                    "$prefix 迭代 #$i：带前缀垃圾解析只能返回 null 或非空串，实际 $parsedPrefixed"
                )
            }
        }
    }

    @Test
    fun `modeLongerThanFortyIsTruncatedAtBoundary`() {
        listOf(39, 40, 41, 80).forEach { length ->
            val mode = "x".repeat(length)
            groups.forEach { (prefix, format, parse) ->
                assertEquals(
                    "x".repeat(minOf(40, length)), parse(format(mode, "Alice")),
                    "$prefix 长度 $length 的 mode 必须截到 40"
                )
            }
        }
    }

    @Test
    fun `separatorsAndSpacesInModeAndHostSurviveRoundTrip`() {
        val modes = listOf("a|b^c", "a b c", "|", "^", "a|", "^b", "  padded  ", "a^b|c d")
        val hosts = listOf("Alice", "Bob|Pipe", "Carol^Hat", "", "A B")
        groups.forEach { (prefix, format, parse) ->
            modes.forEach { mode ->
                hosts.forEach { host ->
                    assertEquals(
                        mode.trim().take(40).ifBlank { null }, parse(format(mode, host)),
                        "$prefix mode=$mode host=$host 往返必须恒等"
                    )
                }
            }
        }
    }
}
