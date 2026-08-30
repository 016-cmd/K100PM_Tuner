package com.k100pm.tuner.v4a2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.ui.AppContextHolder
import com.k100pm.tuner.v4a2.data.ViperContainer
import com.k100pm.tuner.v4a2.ui.screens.main.MainScreen

/**
 * v1.2 音效页（Tab3 入口）。
 *
 * 照搬官方 2.0.3 的 MainScreen（完整效果列表 + 预设 + 设备 + 设置），
 * 叠加我们原有的「驱动检测门控」+「V4A 数据层初始化」。
 *   - 检测不到 libv4a_aidl.so（驱动未安装）→ 不渲染调音页，显示安装提示。
 *   - 检测到 → 渲染官方 MainScreen 完整调音页。
 */
@Composable
fun V4AScreen() {
    var driverOk by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val ctx = AppContextHolder.ctx ?: LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        // 初始化 V4A 数据层（Room DB + DataStore + Repository）
        ViperContainer.init(ctx.applicationContext)
        // 驱动检测门控（沿用旧逻辑：检测 libv4a_aidl.so 是否挂载）
        driverOk = detectDriver()
        checked = true
    }

    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant

    if (!checked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在检测 V4A 音效驱动…", fontSize = 14.sp, color = onSurfaceVariant)
        }
        return
    }

    if (!driverOk) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Text("未检测到 V4A 音效模块（libv4a_aidl.so）", fontSize = 17.sp, color = onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "请先安装并启用音质优化模块：\nREDMI K100 Pro Max 音质优化 by 016. 1.2+\n\n安装或启用后重启设备，再次进入此页面即可。",
                fontSize = 13.sp,
                color = onSurfaceVariant,
            )
        }
        return
    }

    // 驱动就绪：渲染官方 2.0.3 完整音效调音页
    Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        MainScreen()
    }
}

/** 检测 V4A 驱动 so 是否挂载（沿用旧 V4ADriverDetector 逻辑，root 检测）。 */
private fun detectDriver(): Boolean {
    val shell = com.k100pm.tuner.service.WsaShell
    return try {
        val r = shell.execSyncCmd("[ -f /vendor/lib64/soundfx/libv4a_aidl.so ] && echo yes || echo no")
        r.contains("yes")
    } catch (e: Exception) {
        false
    }
}