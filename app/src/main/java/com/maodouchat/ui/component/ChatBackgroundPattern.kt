package com.maodouchat.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * TG 风格聊天背景涂鸦纹理（9.205）。
 *
 * Telegram 经典主题背景布满低对比度小涂鸦（气泡/星星/爱心/圆环/十字等）。
 * 此处用确定性伪随机（seed 固定）在网格上撒点绘制矢量涂鸦，避免引入位图资源；
 * 仅静态绘制一次，无动画开销。颜色由调用方给定（通常为主题辅助色的低透明度变体）。
 */
@Composable
fun ChatBackgroundPattern(
    modifier: Modifier = Modifier,
    tint: Color,
    seed: Int = 7
) {
    Canvas(modifier = modifier) {
        val rng = Random(seed)
        val cell = 96f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                // 网格抖动 + 稀疏留白，观感接近手绘散布
                if (rng.nextFloat() < 0.28f) continue
                val cx = col * cell + cell * 0.5f + (rng.nextFloat() - 0.5f) * cell * 0.6f
                val cy = row * cell + cell * 0.5f + (rng.nextFloat() - 0.5f) * cell * 0.6f
                val r = 9f + rng.nextFloat() * 9f
                val degrees = rng.nextFloat() * 360f
                when (rng.nextInt(6)) {
                    0 -> drawDoodleBubble(tint, Offset(cx, cy), r, degrees)
                    1 -> drawDoodleStar(tint, Offset(cx, cy), r, degrees)
                    2 -> drawDoodleHeart(tint, Offset(cx, cy), r, degrees)
                    3 -> drawCircle(color = tint, radius = r * 0.62f, center = Offset(cx, cy), style = Stroke(width = 2.2f))
                    4 -> drawDoodlePlus(tint, Offset(cx, cy), r * 0.7f, degrees)
                    else -> drawDoodleWave(tint, Offset(cx, cy), r, degrees)
                }
            }
        }
    }
}

/** 对话气泡轮廓（圆角矩形 + 尾巴）。 */
private fun DrawScope.drawDoodleBubble(color: Color, center: Offset, r: Float, degrees: Float) {
    rotate(degrees, pivot = center) {
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = center.x - r * 1.2f,
                    top = center.y - r * 0.9f,
                    right = center.x + r * 1.2f,
                    bottom = center.y + r * 0.7f,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.45f)
                )
            )
            moveTo(center.x - r * 0.5f, center.y + r * 0.68f)
            lineTo(center.x - r * 0.72f, center.y + r * 1.25f)
            lineTo(center.x - r * 0.05f, center.y + r * 0.68f)
            close()
        }
        drawPath(path, color, style = Stroke(width = 2.2f))
    }
}

/** 五角星轮廓。 */
private fun DrawScope.drawDoodleStar(color: Color, center: Offset, r: Float, degrees: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.45f
        val angle = Math.toRadians(i * 36.0 - 90.0)
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    rotate(degrees, pivot = center) {
        drawPath(path, color, style = Stroke(width = 2.2f))
    }
}

/** 爱心轮廓（双圆弧近似）。 */
private fun DrawScope.drawDoodleHeart(color: Color, center: Offset, r: Float, degrees: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y + r * 0.85f)
        cubicTo(
            center.x - r * 1.4f, center.y - r * 0.15f,
            center.x - r * 0.65f, center.y - r * 1.1f,
            center.x, center.y - r * 0.35f
        )
        cubicTo(
            center.x + r * 0.65f, center.y - r * 1.1f,
            center.x + r * 1.4f, center.y - r * 0.15f,
            center.x, center.y + r * 0.85f
        )
        close()
    }
    rotate(degrees, pivot = center) {
        drawPath(path, color, style = Stroke(width = 2.2f))
    }
}

/** 十字/加号。 */
private fun DrawScope.drawDoodlePlus(color: Color, center: Offset, r: Float, degrees: Float) {
    rotate(degrees, pivot = center) {
        drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = 2.4f)
        drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), strokeWidth = 2.4f)
    }
}

/** 波浪短线。 */
private fun DrawScope.drawDoodleWave(color: Color, center: Offset, r: Float, degrees: Float) {
    val path = Path().apply {
        moveTo(center.x - r, center.y)
        cubicTo(
            center.x - r * 0.4f, center.y - r * 0.7f,
            center.x + r * 0.4f, center.y + r * 0.7f,
            center.x + r, center.y
        )
    }
    rotate(degrees, pivot = center) {
        drawPath(path, color, style = Stroke(width = 2.2f))
    }
}
