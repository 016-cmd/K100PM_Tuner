package com.k100pm.tuner.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory

// ── 品牌色 ──
private val AccentGreen = Color(0xFF22C55E)
private val AccentGreenDim = Color(0xFF166534)
private val AccentBlue = Color(0xFF5B9BD5)

private val LightBackground = Color(0xFFF5F5F7)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEBEBF0)
private val LightTextPrimary = Color(0xFF1A1A1E)
private val LightTextSecondary = Color(0xFF6B6B78)

private val DarkBackground = Color(0xFF0D0D0F)
private val DarkSurface = Color(0xFF1A1A1E)
private val DarkSurfaceVariant = Color(0xFF25252B)
private val DarkTextPrimary = Color(0xFFEBEBF0)
private val DarkTextSecondary = Color(0xFFB0B0B8)  // 提亮,深色卡片中可读

private val LightColorScheme = lightColorScheme(
    primary = AccentGreen,
    onPrimary = Color.White,
    primaryContainer = AccentGreenDim.copy(alpha = 0.3f),
    onPrimaryContainer = AccentGreenDim,
    secondary = AccentBlue,
    onSecondary = Color.White,
    secondaryContainer = AccentBlue.copy(alpha = 0.15f),
    onSecondaryContainer = AccentBlue,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFC8C8D0),
    outlineVariant = Color(0xFFE0E0E6),
    error = Color(0xFFCF6679),
    onError = Color.White,
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color(0xFF1A1A1E),
    primaryContainer = AccentGreenDim,
    onPrimaryContainer = Color(0xFFB3F5C0),
    secondary = AccentBlue,
    onSecondary = Color(0xFF0D0D0F),
    secondaryContainer = Color(0xFF1B3A5C),
    onSecondaryContainer = Color(0xFFB8D8F8),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = Color(0xFF3A3A45),
    outlineVariant = Color(0xFF25252B),
    error = Color(0xFFCF6679),
    onError = Color(0xFF0D0D0F),
    scrim = Color.Black
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }

private const val PREFS_THEME = "k100pm_tuner"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_WALLPAPER = "wallpaper_uri"

object ThemePrefs {
    fun getMode(ctx: Context): ThemeMode = try {
        ThemeMode.valueOf(ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE).getString(KEY_THEME_MODE, "SYSTEM")!!)
    } catch (_: Exception) { ThemeMode.SYSTEM }

    fun setMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getWallpaperUri(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE).getString(KEY_WALLPAPER, null)

    fun setWallpaperUri(ctx: Context, uri: String?) {
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE).edit().putString(KEY_WALLPAPER, uri).apply()
    }
}

// ── K100TunerTheme ──
@Composable
fun K100TunerTheme(content: @Composable () -> Unit) {
    // recreate() 后整个 Composable 树重建,直接从 SharedPreferences 读取
    val ctx = LocalContext.current
    val mode = ThemePrefs.getMode(ctx)
    val wallpaperUri = ThemePrefs.getWallpaperUri(ctx)

    val isDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    // 用 onSizeChanged 获取真实窗口宽度 → WallpaperState.scale（Scene 同款:rootView.width/192）
    var windowWidthPx by remember { mutableIntStateOf(1080) }

    // 壁纸变化时异步加载 bitmap → 模糊 → WallpaperState
    // 使用 state 计数器触发 Compose 重组（WallpaperState 是非 Compose 对象）
    var wallpaperVersion by remember { mutableIntStateOf(0) }

    // 窗口宽度或壁纸变化时重新计算 scale
    LaunchedEffect(wallpaperUri, isDark, windowWidthPx) {
        if (wallpaperUri != null) {
            try {
                withContext(Dispatchers.IO) {
                    val bitmap = if (wallpaperUri.startsWith("content://")) {
                        ctx.contentResolver.openInputStream(
                            android.net.Uri.parse(wallpaperUri)
                        )?.use { BitmapFactory.decodeStream(it) }
                    } else {
                        BitmapFactory.decodeFile(wallpaperUri)
                    }
                    if (bitmap != null) {
                        WallpaperState.set(ctx, bitmap, isDark, windowWidthPx)
                    }
                }
                wallpaperVersion++ // 触发重组
            } catch (_: Exception) {}
        } else {
            WallpaperState.clear()
            wallpaperVersion++ // 触发重组
        }
    }

    // 读取 wallpaperVersion 确保 Compose 追踪变化
    val version = wallpaperVersion

    MaterialTheme(colorScheme = colorScheme, typography = Typography()) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .onSizeChanged { size -> windowWidthPx = size.width }
        ) {
            // 全屏壁纸:直接用 WallpaperState 原始 Bitmap,与模糊壁纸同一来源
            @Suppress("UNUSED_EXPRESSION")
            version // 读取触发重组
            val wallpaperBitmap = WallpaperState.original
            if (wallpaperBitmap != null) {
                Image(
                    bitmap = wallpaperBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // 内容层直接叠加在壁纸（或纯色背景）上
            content()
        }
    }
}