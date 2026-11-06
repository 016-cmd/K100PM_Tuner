package com.k100pm.tuner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.service.DapRealtime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 0 dB 磁吸范围：±0.25 dB（4 Q4）以内自动归零 */
private const val ZERO_SNAP_Q4 = 4

/**
 * 单段竖向滑杆（自绘轨道 + 刻度 + 滑块）
 *
 * 为什么不用 Material 的 Slider 旋转：
 *   旋转后的 Slider 触摸区域形状不规则，会和外层横向滚动抢手势，
 *   而且 20 段挤在 36 dp 宽里几乎点不准。
 *   这里用 Canvas 自己画，滑块热区固定 44 dp，拖动时 consume 掉事件，
 *   外层 LazyRow 只负责左右滑动，互不干扰。
 *
 * @param valueQ4 当前值（Q4，1 dB = 16）
 * @param maxAbsQ4 上下限绝对值（默认 ±384 = ±24 dB）
 */
@Composable
fun DapBandSlider(
    freqLabel: String,
    valueQ4: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxAbsQ4: Int = DapRealtime.MAX_BOOST_Q4,
    trackHeight: Dp = 320.dp,
    accent: Color = Color(0xFF22C55E),
    trackColor: Color = Color(0xFF2E3440),
    tickColor: Color = Color(0xFF6B7280),
    valueColor: Color = Color.Unspecified,
    freqColor: Color = Color.Unspecified,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
) {
    val density = LocalDensity.current
    val trackPx = with(density) { trackHeight.toPx() }
    val insetPx = with(density) { 10.dp.toPx() }
    val thumbTouch = 44.dp
    val thumbTouchPx = with(density) { thumbTouch.toPx() }

    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentValue by rememberUpdatedState(valueQ4)
    val haptic = LocalHapticFeedback.current

    var dragging by remember { mutableStateOf(false) }
    var dragQ4 by remember { mutableDoubleStateOf(valueQ4.toDouble()) }

    // 外部值变化（读回 / 恢复草稿）且不在拖动时，同步到本段
    SideEffect {
        if (!dragging) dragQ4 = valueQ4.toDouble()
    }

    val lo = -maxAbsQ4.toFloat()
    val hi = maxAbsQ4.toFloat()

    fun yFor(q4: Int): Float {
        val usable = (trackPx - insetPx * 2f).coerceAtLeast(1f)
        val f = ((q4 - lo) / (hi - lo)).coerceIn(0f, 1f)
        return insetPx + usable * (1f - f)
    }

    val db = valueQ4 / DapRealtime.Q4_PER_DB.toFloat()
    val dbText = String.format(Locale.US, "%+.1f", db)
    val display = if (valueColor == Color.Unspecified) Color(0xFFE5E7EB) else valueColor
    val freqTextColor = if (freqColor == Color.Unspecified) Color(0xFF9CA3AF) else freqColor

    Column(
        modifier = modifier
            .width(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 数值
        Text(
            dbText,
            modifier = Modifier.fillMaxWidth(),
            color = if (db != 0f) accent else display,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))

        // 轨道（粗胶囊长条 + 刻度 + 填充 + 端头横条）
        Box(Modifier.width(44.dp).height(trackHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val top = insetPx
                val bottom = size.height - insetPx
                val usable = (bottom - top).coerceAtLeast(1f)
                val zeroY = top + usable / 2f
                val thumbY = yFor(valueQ4)

                val barW = 22.dp.toPx()
                val barRadius = CornerRadius(barW / 2f, barW / 2f)

                // 刻度：尽量淡，放在长条后面
                var q4 = maxAbsQ4
                while (q4 >= -maxAbsQ4) {
                    val y = yFor(q4)
                    val major = q4 % (3 * DapRealtime.Q4_PER_DB) == 0
                    val len = if (major) 30.dp.toPx() else 20.dp.toPx()
                    drawLine(
                        color = tickColor.copy(alpha = if (major) 0.20f else 0.10f),
                        start = Offset(centerX - len / 2f, y),
                        end = Offset(centerX + len / 2f, y),
                        strokeWidth = if (major) 1.dp.toPx() else 0.7.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    q4 -= DapRealtime.Q4_PER_DB
                }

                // 底槽（整根长条）
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(centerX - barW / 2f, top),
                    size = Size(barW, usable),
                    cornerRadius = barRadius
                )

                // 填充：从 0 dB 到当前值（上绿下琥珀，带渐变）
                if (valueQ4 != 0) {
                    val isUp = valueQ4 > 0
                    val fillTop = minOf(zeroY, thumbY)
                    val fillH = abs(zeroY - thumbY).coerceAtLeast(1f)
                    val base = if (isUp) accent else Color(0xFFF59E0B)
                    val brush = if (isUp) {
                        Brush.verticalGradient(
                            colors = listOf(base.copy(alpha = 0.45f), base),
                            startY = zeroY, endY = zeroY - fillH
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(base.copy(alpha = 0.45f), base),
                            startY = zeroY, endY = zeroY + fillH
                        )
                    }
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(centerX - barW / 2f, fillTop),
                        size = Size(barW, fillH),
                        cornerRadius = barRadius
                    )
                }

                // 0 dB 基准（中线做得明显一点，方便“看准回 0 的位置”）
                drawLine(
                    color = Color.White.copy(alpha = 0.55f),
                    start = Offset(centerX - barW / 2f - 6.dp.toPx(), zeroY),
                    end = Offset(centerX + barW / 2f + 6.dp.toPx(), zeroY),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // 中线两侧的小卡扣（视觉上就是“0 的凹槽”）
                drawCircle(Color.White.copy(alpha = 0.5f), 1.6.dp.toPx(),
                    Offset(centerX - barW / 2f - 7.dp.toPx(), zeroY))
                drawCircle(Color.White.copy(alpha = 0.5f), 1.6.dp.toPx(),
                    Offset(centerX + barW / 2f + 7.dp.toPx(), zeroY))

                // 端头横条（代替圆点，长条风格的“把手”）
                val capW = barW + 10.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.92f),
                    topLeft = Offset(centerX - capW / 2f, thumbY - 1.75.dp.toPx()),
                    size = Size(capW, 3.5.dp.toPx()),
                    cornerRadius = CornerRadius(1.75.dp.toPx(), 1.75.dp.toPx())
                )
                // 端头内芯（绿色描一条，保持品牌色）
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(centerX - capW / 2f + 2.dp.toPx(), thumbY - 0.9.dp.toPx()),
                    size = Size(capW - 4.dp.toPx(), 1.8.dp.toPx()),
                    cornerRadius = CornerRadius(0.9.dp.toPx(), 0.9.dp.toPx())
                )

                if (selected) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.14f),
                        topLeft = Offset(centerX - barW / 2f - 2.dp.toPx(), top),
                        size = Size(barW + 4.dp.toPx(), usable),
                        cornerRadius = CornerRadius(barW / 2f + 2.dp.toPx(), barW / 2f + 2.dp.toPx())
                    )
                }
            }

            // 拖动热区（44 dp，跟着滑块走）
            val thumbY = yFor(valueQ4)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (thumbY - thumbTouchPx / 2f)
                                .coerceIn(0f, (trackPx - thumbTouchPx).coerceAtLeast(0f))
                                .roundToInt()
                        )
                    }
                    .size(thumbTouch)
                    .pointerInput(maxAbsQ4) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                dragQ4 = currentValue.toDouble()
                                currentOnSelect()
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, dragAmount ->
                            // 吃掉横纵两个方向的事件，避免漏给外层 LazyRow
                            change.consume()
                            val usable = (trackPx - insetPx * 2f).coerceAtLeast(1f)
                            var next = dragQ4 - dragAmount.y * (hi - lo) / usable
                            next = next.coerceIn(lo.toDouble(), hi.toDouble())
                            var snapped = next.roundToInt()

                            // ── 0 dB 磁吸：±0.25 dB（±4 Q4）内自动归零 ──
                            // 所以「+0.1 和 -0.1 太近调不回 0」的问题不存在了：
                            // 手指一靠近中线就直接落到 0，还带一下轻震反馈。
                            if (abs(snapped) <= ZERO_SNAP_Q4) snapped = 0

                            val prev = dragQ4.roundToInt()
                            if (snapped != prev) {
                                if (snapped == 0 && prev != 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                dragQ4 = snapped.toDouble()
                                currentOnChange(snapped)
                            }
                        }
                    }
                    // 双击直接归零（比拖到中间更精确）
                    .pointerInput(maxAbsQ4) {
                        detectTapGestures(
                            onDoubleTap = {
                                currentOnSelect()
                                if (currentValue != 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnChange(0)
                                }
                            }
                        )
                    }
            )
        }

        Spacer(Modifier.height(4.dp))
        // 频率
        Text(
            freqLabel,
            modifier = Modifier.fillMaxWidth(),
            color = if (selected) accent else freqTextColor,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}