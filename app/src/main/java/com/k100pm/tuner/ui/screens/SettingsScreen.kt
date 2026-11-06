package com.k100pm.tuner.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k100pm.tuner.ui.components.GlassSettingsCard
import com.k100pm.tuner.ui.components.AppearanceSection
import com.k100pm.tuner.ui.theme.DebugMode
import com.k100pm.tuner.ui.theme.ThemeMode
import com.k100pm.tuner.ui.theme.ThemePrefs
import com.k100pm.tuner.ui.theme.Appearance
import com.k100pm.tuner.ui.theme.subInk
import java.io.File
import java.io.FileOutputStream
import com.k100pm.tuner.ui.theme.jellyPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: Activity,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var debugTaps by remember { mutableStateOf(0) }
    var showDebug by remember { mutableStateOf(false) }
    val bump: () -> Unit = {
        debugTaps += 1
        if (debugTaps >= DebugMode.TAPS) {
            debugTaps = 0
            showDebug = true
        }
    }
    var themeMode by remember { mutableStateOf(ThemePrefs.getMode(ctx)) }
    var wallpaperUri by remember { mutableStateOf(ThemePrefs.getWallpaperUri(ctx)) }
    var wallpaperKind by remember { mutableIntStateOf(ThemePrefs.getWallpaperKind(ctx)) }
    var readLevel by remember { mutableIntStateOf(Appearance.readLevel) }
    var cardRadius by remember { mutableFloatStateOf(Appearance.radius) }
    var showLicense by remember { mutableStateOf(false) }
    val licenseText = remember {
        runCatching {
            ctx.assets.open("license_gpl3.txt").bufferedReader().use { it.readText() }
        }.getOrNull() ?: ""
    }
    val appVersion = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 复制到内部存储 → Scene 同款 windowBg.jpg
            try {
                val bgFile = File(ctx.filesDir, "windowBg.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(bgFile).use { output ->
                        input.copyTo(output)
                    }
                }
                ThemePrefs.setWallpaperUri(ctx, bgFile.path)
            } catch (_: Exception) {
                // 回退:直接存 URI
                ThemePrefs.setWallpaperUri(ctx, uri.toString())
            }
            activity.recreate()
        }
    }

    if (showDebug) {
        DebugUnlockDialog(ctx = ctx, onDismiss = { showDebug = false })
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── 外观 ──
            SectionHeader("外观")
            Spacer(Modifier.height(6.dp))

            GlassSettingsCard {
                ThemeOption(
                    label = "浅色",
                    icon = Icons.Rounded.LightMode,
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = {
                        themeMode = ThemeMode.LIGHT
                        ThemePrefs.setMode(ctx, ThemeMode.LIGHT)
                        activity.recreate()
                    }
                )
                ThemeOption(
                    label = "深色",
                    icon = Icons.Rounded.DarkMode,
                    selected = themeMode == ThemeMode.DARK,
                    onClick = {
                        themeMode = ThemeMode.DARK
                        ThemePrefs.setMode(ctx, ThemeMode.DARK)
                        activity.recreate()
                    }
                )
                ThemeOption(
                    label = "跟随系统",
                    icon = Icons.Rounded.SettingsBrightness,
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = {
                        themeMode = ThemeMode.SYSTEM
                        ThemePrefs.setMode(ctx, ThemeMode.SYSTEM)
                        activity.recreate()
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 外观与个性化 ──
            SectionHeader("外观")
            Spacer(Modifier.height(6.dp))

            AppearanceSection(
                onPickWallpaper = { wallpaperPicker.launch("image/*") },
                onClearWallpaper = {
                    ThemePrefs.setWallpaperUri(ctx, null)
                    File(ctx.filesDir, "windowBg.jpg").delete()
                    activity.recreate()
                },
                hasLocalWallpaper = wallpaperUri != null,
                wallpaperKind = if (wallpaperUri != null) 0 else wallpaperKind,
                onWallpaperKind = { k ->
                    if (k == 0 && wallpaperUri == null) {
                        wallpaperPicker.launch("image/*")
                    } else {
                        ThemePrefs.setWallpaperUri(ctx, null)
                        ThemePrefs.setWallpaperKind(ctx, k)
                        File(ctx.filesDir, "windowBg.jpg").delete()
                        activity.recreate()
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── 关于 ──
            SectionHeader("关于")
            Spacer(Modifier.height(6.dp))
            GlassSettingsCard {
                InfoRow("版本", "v$appVersion") { bump() }
                InfoRow("开发者", "016-cmd")
                InfoRow("设备", "REDMI K100 Pro Max")
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                SettingsRow(
                    icon = Icons.Rounded.Description,
                    label = "开源许可证",
                    subtitle = "GNU General Public License v3.0",
                    onClick = { showLicense = true }
                )
            }
                    Spacer(Modifier.height(60.dp))
}
    }
    if (showLicense) {
        Dialog(onDismissRequest = { showLicense = false }) {
            LicenseDialogContent(
                title = "开源许可 — GNU GPL v3.0",
                summary = "本工程基于 GNU General Public License v3.0（GPL-3.0）发布。\n" +
                    "使用、修改、分发本工程须遵循 GPL-3.0 条款并保留版权声明。",
                original = licenseText,
                onDismiss = { showLicense = false }
            )
        }
    }
}

@Composable
private fun LicenseDialogContent(
    title: String,
    summary: String,
    original: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxHeight(0.9f)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 固定顶部标题栏 + 右上角返回按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "返回"
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 可滚动正文区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    "许可证原文",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    original.ifBlank { "未找到许可证原文。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
            }

            // 固定底部返回按钮
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("返回")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun ThemeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .jellyPress(com.k100pm.tuner.ui.theme.Appearance.jelly > 0).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .jellyPress(com.k100pm.tuner.ui.theme.Appearance.jelly > 0).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null)
                    Modifier.jellyPress(Appearance.jelly > 0).clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DebugUnlockDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    val on = DebugMode.on(ctx)
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (on) "调试模式（已开启）" else "调试模式") },
        text = {
            Column {
                Text(
                    if (on) "输入口令可关闭：关闭后恢复按模块状态隐藏页面。"
                    else "输入口令后，即使未安装模块也会显示全部界面（仅预览，不会真的调音）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; err = false },
                    singleLine = true,
                    isError = err,
                    label = { Text("口令") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                if (err) Text("口令不对", fontSize = 11.sp, color = Color(0xFFEF4444))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (on) {
                    DebugMode.set(ctx, false)
                    onDismiss()
                } else if (text.trim() == DebugMode.PASS) {
                    DebugMode.set(ctx, true)
                    onDismiss()
                } else {
                    err = true
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
