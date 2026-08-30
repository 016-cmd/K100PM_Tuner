package com.k100pm.tuner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * 频响曲线（Medium 场景实时预览）
 *
 * X 轴:按 points 列表顺序【均匀分布】,曲线第 i 个点横向位置与下方滑杆第 i 个频段严格对齐,
 *       彻底避免"对数刻度导致点位与频段列表错位"的问题.
 *       底部标注真实频段值（20 频段自动抽样,避免拥挤）.
 * Y 轴:absolute 模式下自适应当前增益,并画 0 参考线;delta 模式下固定 ±250 带 0 中线.
 *
 * @param points 频率升序的 (频率, 增益) 列表
 * @param lineColor 曲线颜色
 * @param showDelta 是否偏移模式（Y 基准 0 中线）
 */
@Composable
fun BandCurveCanvas(
    points: List<Pair<Int, Int>>,
    lineColor: Color = Color(0xFF22C55E),
    showDelta: Boolean = false
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = onSurfaceVariant, fontSize = 8.sp)

    val sorted = points.sortedBy { it.first }
    val n = sorted.size

    // Y 范围:delta 模式固定 -250~250;absolute 模式自适应
    val gMin: Float
    val gMax: Float
    if (showDelta) {
        gMin = -250f
        gMax = 250f
    } else {
        val gs = sorted.map { it.second }
        gMin = (min(gs.minOrNull() ?: 0, -80)).toFloat()
        gMax = (max(gs.maxOrNull() ?: 0, 80)).toFloat()
    }
    val gSpan = (gMax - gMin).coerceAtLeast(1f)

    // X 轴下方要标注的真实频段（抽样,避免拥挤）
    // 4 频段(低音)全标;20 频段(左/右)隔 3 个标一个
    val labelStride = if (n <= 6) 1 else 3

    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        val w = size.width
        val h = size.height
        val padL = 10f
        val padR = 10f
        val padT = 18f
        val padB = 22f
        val pl = padL; val pr = w - padR; val pt = padT; val pb = h - padB

        if (n == 0) return@Canvas

        fun xOf(i: Int): Float =
            if (n == 1) (pl + pr) / 2f else pl + i.toFloat() / (n - 1) * (pr - pl)

        fun yOf(g: Int): Float {
            val ratio = (g.toFloat() - gMin) / gSpan
            return pb - ratio * (pb - pt)
        }

        // ── 横向网格基线 ──
        val y0 = yOf(0).coerceIn(pt, pb)
        drawLine(
            color = onSurfaceVariant.copy(alpha = 0.28f),
            start = Offset(pl, y0), end = Offset(pr, y0), strokeWidth = 1f
        )
        listOf(1f, 0f).forEach { frame ->
            val y = pt + frame * (pb - pt)
            drawLine(
                color = onSurfaceVariant.copy(alpha = 0.12f),
                start = Offset(pl, y), end = Offset(pr, y), strokeWidth = 1f
            )
        }
        // Y 轴增益标注（顶部=最大值,底部=最小值）
        val topLabel = textMeasurer.measure(gMax.toInt().toString(), labelStyle)
        drawText(topLabel, topLeft = Offset(2f, pt - 6), color = onSurfaceVariant.copy(alpha = 0.5f))
        val botLabel = textMeasurer.measure(gMin.toInt().toString(), labelStyle)
        drawText(botLabel, topLeft = Offset(2f, pb - 9), color = onSurfaceVariant.copy(alpha = 0.5f))

        // ── 每个数据的 x 位置纵向对齐线 + 底部频段标注 ──
        val xByIndex = (0 until n).map { xOf(it) }
        xByIndex.forEachIndexed { i, x ->
            drawLine(
                color = onSurfaceVariant.copy(alpha = 0.08f),
                start = Offset(x, pt), end = Offset(x, pb), strokeWidth = 1f
            )
            if (i % labelStride == 0 || i == n - 1) {
                val f = sorted[i].first
                val lab = textMeasurer.measure(if (f >= 1000) "${f / 1000}k" else "$f", labelStyle)
                drawText(lab, topLeft = Offset(x - lab.size.width / 2, pb + 3), color = onSurfaceVariant.copy(alpha = 0.6f))
            }
        }

        // ── 折线 ──
        if (n >= 2) {
            val path = Path()
            path.moveTo(xByIndex[0], yOf(sorted[0].second))
            for (i in 1 until n) {
                path.lineTo(xByIndex[i], yOf(sorted[i].second))
            }
            clipRect(left = pl, right = pr, top = pt, bottom = pb) {
                drawPath(path, color = lineColor, style = Stroke(width = 2f))
            }

            // 顶点描点
            sorted.forEachIndexed { i, (_, g) ->
                drawCircle(color = lineColor, radius = 3f, center = Offset(xByIndex[i], yOf(g)))
            }
        }
    }
}
