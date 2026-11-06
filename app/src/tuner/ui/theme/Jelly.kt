package com.k100pm.tuner.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * 果冻按压：按下时横向放宽、纵向压扁，松手弹回。
 * 只给按钮 / 可点行用，卡片本体不要套。
 */
@Composable
fun Modifier.jellyPress(enabled: Boolean): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    val amt = (Appearance.jelly / 100f).coerceIn(0f, 1f)
    if (amt <= 0f) return this
    val pressX = 1f + 0.05f * amt
    val pressY = 1f - 0.07f * amt
    val spec = spring<Float>(
        dampingRatio = Appearance.springDamping(),
        stiffness = Appearance.springStiffness(),
        visibilityThreshold = 0.001f
    )
    return this
        .graphicsLayer {
            scaleX = sx.value
            scaleY = sy.value
        }
        .pointerInput(enabled, amt) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { sx.animateTo(pressX, spec) }
                scope.launch { sy.animateTo(pressY, spec) }
                waitForUpOrCancellation()
                scope.launch { sx.animateTo(1f, spec) }
                scope.launch { sy.animateTo(1f, spec) }
            }
        }
}
/** 切页计数：换页时 +1，让卡片入场动画重播 */
val LocalCardEpoch = compositionLocalOf { 0 }

/**
 * 卡片入场：透明度 0→1 + 缩放 0.965→1.0（弹簧）。
 * 与 DolbyTuner 同款；LocalCardEpoch 变化时重播。
 */
@Composable
fun Modifier.cardEnter(enabled: Boolean): Modifier {
    if (!enabled) return this
    val anim = remember { Animatable(0f) }
    val epoch = LocalCardEpoch.current
    LaunchedEffect(epoch) {
        anim.snapTo(0f)
        anim.animateTo(
            1f,
            spring(dampingRatio = 0.78f, stiffness = 240f, visibilityThreshold = 0.001f)
        )
    }
    return this.graphicsLayer {
        val p = anim.value
        alpha = p.coerceIn(0f, 1f)
        val s = 0.965f + 0.035f * p
        scaleX = s
        scaleY = s
    }
}
