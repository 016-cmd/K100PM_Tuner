package com.k100pm.tuner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.k100pm.tuner.ui.theme.Appearance
import com.k100pm.tuner.ui.theme.cardEnter
import com.k100pm.tuner.ui.theme.glassSurface

/**
 * 液态玻璃卡片（Backdrop 真折射）。
 *
 * 采样的是 Theme 里那一层「壁纸层 backdrop」，
 * 不是每张卡各抓一次背景 —— 不会踩 RenderThread 递归崩溃。
 *
 * Backdrop 不可用（API<33 / 省电档）时退回纯色玻璃。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val radius = Appearance.radius
    Box(
        modifier = modifier
            .cardEnter(Appearance.animCard == 1)
            .clip(RoundedCornerShape(radius.dp))
            .glassSurface(radius)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            content()
        }
    }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Float = Appearance.radius,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .cardEnter(Appearance.animCard == 1)
            .clip(RoundedCornerShape(radius.dp))
            .glassSurface(radius)
    ) {
        content()
    }
}

@Composable
fun GlassSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val radius = (Appearance.radius - 8f).coerceAtLeast(10f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .cardEnter(Appearance.animCard == 1)
            .clip(RoundedCornerShape(radius.dp))
            .glassSurface(radius)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            content()
        }
    }
}
