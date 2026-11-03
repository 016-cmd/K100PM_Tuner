package com.k100pm.tuner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.ui.MainViewModel

/**
 * 顶部模块检测卡片 — K100PM 音质模块伴生 APP.
 * 显示 Magisk 模块安装状态 + Root 状态.
 * 使用 GlassCard 液态玻璃风格.
 */
@Composable
fun ModuleStatusCard(
    status: MainViewModel.ModuleStatus,
    hasRoot: Boolean?,
    onRefresh: () -> Unit,
    onActivate: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ── 标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            val indicatorColor = when {
                status.isChecking -> MaterialTheme.colorScheme.outline
                status.isInstalled && hasRoot == true -> Color(0xFF4CAF50)
                else -> Color(0xFFCF6679)
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(indicatorColor))
            Spacer(Modifier.width(12.dp))
            Text(
                "K100PM 音质模块",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(14.dp))

        if (status.isChecking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在检测...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            InfoRow("版本", status.version)
            InfoRow("类型", status.edition)

            Spacer(Modifier.height(6.dp))

            // ── Root 状态 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text("Root", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                Icon(
                    if (hasRoot == true) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                    null,
                    tint = if (hasRoot == true) Color(0xFF4CAF50) else Color(0xFFCF6679),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (hasRoot == true) "已获取" else if (hasRoot == null) "未检测" else "未获取",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasRoot == true) Color(0xFF4CAF50) else Color(0xFFCF6679)
                )
            }

            // ── 激活按钮 ──
            if (hasRoot == null || hasRoot == false) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.Security, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("授权 Root 并激活", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            // ── 状态提示 ──
            val hintText = when {
                status.isInstalled && hasRoot == true -> null
                !status.isInstalled && hasRoot == true -> "检测到未安装 REDMI K100 Pro Max 音质优化 by 016. 模块\n请先刷入 Magisk 音质模块"
                hasRoot == false -> "请在 Magisk 中永久授权本 APP"
                hasRoot == null -> "点击上方按钮获取 Root 权限\n在 Magisk 弹窗中授权即可"
                else -> null
            }
            if (hintText != null) {
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0xFFCF6679).copy(alpha = 0.1f), shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(hintText, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCF6679).copy(alpha = 0.9f), lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}