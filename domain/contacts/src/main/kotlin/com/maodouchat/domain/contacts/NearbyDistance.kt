package com.maodouchat.domain.contacts

import java.text.NumberFormat

// 附近的人距离文案：米/公里两档纯判定。自 app 侧 ExploreNearbyRows.kt 逐字平移，
// 资源 id 换成 sealed 结果，R.string 映射留 app 侧。
sealed interface NearbyDistanceLabel {
    data class Meters(val meters: Int) : NearbyDistanceLabel
    data class Kilometers(val formattedKm: String) : NearbyDistanceLabel
}

// NumberFormat 非线程安全，ThreadLocal 每线程复用一个；距离文案固定一位小数。
private val nearbyDistanceFormat: ThreadLocal<NumberFormat> =
    ThreadLocal.withInitial {
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }

fun nearbyDistanceLabel(distanceMeters: Int): NearbyDistanceLabel =
    if (distanceMeters < 1_000) {
        // 定位精度有限，显示「约 100 米」比「约 0 米」更可信。
        NearbyDistanceLabel.Meters(distanceMeters.coerceAtLeast(100))
    } else {
        NearbyDistanceLabel.Kilometers(nearbyDistanceFormat.get().format(distanceMeters / 1_000.0))
    }

// 半径选项格式化：整数 0 位小数、非整数 1 位。自 ExploreNearbyScreen.kt 行内逻辑逐字抽出。
private val nearbyRadiusFormat: ThreadLocal<NumberFormat> =
    ThreadLocal.withInitial { NumberFormat.getNumberInstance() }

fun formatNearbyRadiusOption(optionKm: Double): String =
    nearbyRadiusFormat.get().apply {
        maximumFractionDigits = if (optionKm % 1.0 == 0.0) 0 else 1
    }.format(optionKm)
