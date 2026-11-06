package com.k100pm.tuner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全局壁纸层 backdrop。
 *
 * Theme 里只有「壁纸那一层」被 layerBackdrop 记录下来，
 * 所有液态玻璃卡片都从这一层采样 —— 卡片自己不在这一层里，
 * 不会出现「卡片采样自己」的递归，也就不会踩 RenderThread 崩溃。
 */
object GlassBackdrop {
    @Volatile
    var instance: Backdrop? = null
}

/**
 * 液态玻璃表面：参数与 DolbyTuner 完全同一套
 * （折射 / 边缘宽度 / 模糊 / 提亮 / 色散 / 光源角度 / 体积光 / 厚度 / 性能档）。
 * Backdrop 不可用时退回纯色玻璃。
 */
@Composable
fun Modifier.glassSurface(radius: Float = Appearance.radius): Modifier {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.red * 0.299f + cs.background.green * 0.587f +
            cs.background.blue * 0.114f < 0.5f

    val on = Appearance.enabled
    val tint = Appearance.tintColor(isDark)
    val tintAlpha = Appearance.tintAlpha(isDark)
    val refr = Appearance.refraction / 100f
    val blurDp = (Appearance.blurRadius * 0.22f).coerceIn(0f, 5f)
    val vib = Appearance.vibrancy / 100f
    val angle = Appearance.lightAngle.toFloat()
    val vol = Appearance.volumeLight
    val disp = Appearance.dispersion

    val density = LocalDensity.current
    val rPx = with(density) { radius.dp.toPx() }
    val strokePx = with(density) { Appearance.strokeDp().dp.toPx() }

    val bd = GlassBackdrop.instance
    val useBackdrop = on && Appearance.cardLiquid && bd != null &&
            Appearance.perfMode != 2 && android.os.Build.VERSION.SDK_INT >= 33

    if (useBackdrop) {
        val shape = RoundedCornerShape(radius.dp)
        return this
            .drawBackdrop(
                backdrop = bd!!,
                shape = { shape },
                effects = {
                    if (blurDp > 0f) blur(blurDp.dp.toPx())
                    vibrancy()
                    if (refr > 0f) {
                        lens(
                            (10f + 12f * refr).dp.toPx(),
                            (18f + 30f * refr).dp.toPx(),
                            disp > 0
                        )
                    }
                },
                onDrawSurface = {
                    // 底色压薄，让折射与壁纸透出来
                    drawRoundRect(
                        color = tint.copy(alpha = tintAlpha * 0.62f),
                        cornerRadius = CornerRadius(rPx, rPx)
                    )
                }
            )
            .drawWithCache {
                onDrawBehind {
                    val w = size.width
                    val h = size.height
                    val corner = CornerRadius(rPx, rPx)
                    // 顶部亮线：方向由光源角度决定，强度由提亮 + 体积光决定
                    val light = vib * (0.5f + 0.5f * (vol / 3f))
                    if (light > 0f) {
                        val rad = Math.toRadians(angle.toDouble())
                        val dx = cos(rad).toFloat()
                        val dy = sin(rad).toFloat()
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.04f + 0.16f * light),
                                0.5f to Color.White.copy(alpha = 0.02f),
                                1f to Color.Transparent,
                                start = Offset(w / 2f - dx * w, h / 2f - dy * h),
                                end = Offset(w / 2f + dx * w, h / 2f + dy * h)
                            ),
                            cornerRadius = corner
                        )
                    }
                    // 外框：浅色淡灰，深色淡白
                    drawRoundRect(
                        color = if (isDark) Color.White.copy(alpha = 0.12f)
                        else Color.Black.copy(alpha = 0.10f),
                        cornerRadius = corner,
                        style = Stroke(strokePx)
                    )
                    // 内侧一圈暗边，增加玻璃厚度
                    drawRoundRect(
                        color = Color.Black.copy(alpha = if (isDark) 0.10f else 0.04f),
                        topLeft = Offset(strokePx, strokePx),
                        size = androidx.compose.ui.geometry.Size(
                            w - strokePx * 2f,
                            h - strokePx * 2f
                        ),
                        cornerRadius = corner,
                        style = Stroke(strokePx * 0.8f)
                    )
                }
            }
    }

    // ── 回退：纯色玻璃 ──
    return this.drawWithCache {
        onDrawBehind {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(rPx, rPx)
            drawRoundRect(
                color = tint.copy(alpha = if (on) tintAlpha else if (isDark) 0.88f else 0.94f),
                cornerRadius = corner
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (isDark) 0.10f else 0.30f),
                    0.20f to Color.Transparent
                ),
                cornerRadius = corner
            )
            drawRoundRect(
                color = if (isDark) Color.White.copy(alpha = 0.12f)
                else Color.Black.copy(alpha = 0.10f),
                cornerRadius = corner,
                style = Stroke(strokePx)
            )
        }
    }
}

/**
 * dock 专用玻璃：与 DolbyTuner 完全同一套自绘实现。
 * 不参与外观自定义、不采样 backdrop（固定参数），避免用户把 dock 调花。
 */
@Composable
fun Modifier.dockSurface(): Modifier {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.red * 0.299f + cs.background.green * 0.587f +
            cs.background.blue * 0.114f < 0.5f
    val density = LocalDensity.current
    val strokePx = with(density) { 1.dp.toPx() }

    return this.drawWithCache {
        onDrawBehind {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(h / 2f, h / 2f)
            // 固定的悬浮玻璃底
            drawRoundRect(
                color = if (isDark) Color.Black.copy(alpha = 0.30f)
                else Color.White.copy(alpha = 0.42f),
                cornerRadius = corner
            )
            // 顶部高光
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (isDark) 0.14f else 0.55f),
                    0.45f to Color.Transparent
                ),
                cornerRadius = corner
            )
            // 外框
            drawRoundRect(
                color = if (isDark) Color.White.copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.60f),
                cornerRadius = corner,
                style = Stroke(strokePx)
            )
        }
    }
}