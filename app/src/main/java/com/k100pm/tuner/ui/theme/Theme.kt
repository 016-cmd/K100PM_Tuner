package com.k100pm.tuner.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 中性色（不跟壁纸，纯灰阶体系）──
private val LightBackground = Color(0xFFF5F5F7)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEBEBF0)
private val LightTextPrimary = Color(0xFF1A1A1E)
private val LightTextSecondary = Color(0xFF3C3C46)   // 提亮：透明卡片上也看得清

private val DarkBackground = Color(0xFF0D0D0F)
private val DarkSurface = Color(0xFF1A1A1E)
private val DarkSurfaceVariant = Color(0xFF25252B)
private val DarkTextPrimary = Color(0xFFEBEBF0)
private val DarkTextSecondary = Color(0xFFDCDCE4)    // 提亮

/**
 * 基础色板。强调色（primary / secondary / tertiary）与
 * onSurfaceVariant 在 K90TunerTheme 里按壁纸与可读性覆盖。
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF16A34A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16A34A).copy(alpha = 0.18f),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2563EB).copy(alpha = 0.15f),
    onSecondaryContainer = Color(0xFF1E3A8A),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFC8C8D0),
    outlineVariant = Color(0xFFE0E0E6),
    error = Color(0xFFDC2626),
    onError = Color.White,
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF101014),
    primaryContainer = Color(0xFF166534),
    onPrimaryContainer = Color(0xFFB3F5C0),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF101014),
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
    error = Color(0xFFEF4444),
    onError = Color(0xFF101014),
    scrim = Color.Black
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }

private const val PREFS_THEME = "k100pm_tuner"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_WALLPAPER = "wallpaper_uri"
private const val KEY_WALLPAPER_KIND = "wallpaper_kind"

object ThemePrefs {
    fun getMode(ctx: Context): ThemeMode = try {
        ThemeMode.valueOf(
            ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, "SYSTEM")!!
        )
    } catch (_: Exception) { ThemeMode.SYSTEM }

    fun setMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getWallpaperUri(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
            .getString(KEY_WALLPAPER, null)

    fun setWallpaperUri(ctx: Context, uri: String?) {
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WALLPAPER, uri).apply()
    }

    /** 内置壁纸：1 绝区零 / 2 海风 / 3 微醺 / 0 纯色 */
    fun getWallpaperKind(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
            .getInt(KEY_WALLPAPER_KIND, 1)

    fun setWallpaperKind(ctx: Context, kind: Int) {
        ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WALLPAPER_KIND, kind).apply()
    }
}

// ── K90TunerTheme ──
@Composable
fun K100TunerTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current

    // recreate() 后整个 Composable 树重建，直接从 SharedPreferences 读
    val mode = ThemePrefs.getMode(ctx)
    val wallpaperUri = ThemePrefs.getWallpaperUri(ctx)
    val wallpaperKind = ThemePrefs.getWallpaperKind(ctx)

    // 外观参数（圆角 / 玻璃档 / 果冻 / 可读性）
    LaunchedEffect(Unit) { Appearance.load(ctx) }

    val isDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // 主题色按壁纸派生（Appearance 是 Compose state，换壁纸会触发重组）
    val accent = Appearance.accent
    val second = Appearance.secondary
    val third = Appearance.tertiary
    val readLevel = Appearance.readLevel

    val base = if (isDark) DarkColorScheme else LightColorScheme
    val colorScheme = base.copy(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.55f) Color(0xFF101014) else Color.White,
        primaryContainer = accent.copy(alpha = 0.18f),
        onPrimaryContainer = accent,
        secondary = second,
        onSecondary = if (second.luminance() > 0.55f) Color(0xFF101014) else Color.White,
        secondaryContainer = second.copy(alpha = 0.15f),
        onSecondaryContainer = second,
        tertiary = third,
        onTertiary = if (third.luminance() > 0.55f) Color(0xFF101014) else Color.White,
        onSurfaceVariant = when {
            readLevel >= 1 -> if (isDark) Color.White else Color.Black
            isDark -> DarkTextSecondary
            else -> LightTextSecondary
        },
        onSurface = if (readLevel >= 1) (if (isDark) Color.White else Color.Black) else base.onSurface
    )

    // 用 onSizeChanged 获取真实窗口宽度 → WallpaperState.scale
    var windowWidthPx by remember { mutableIntStateOf(1080) }
    var wallpaperVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(wallpaperUri, wallpaperKind, isDark, windowWidthPx) {
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                if (wallpaperUri != null) {
                    if (wallpaperUri.startsWith("content://")) {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(wallpaperUri))
                            ?.use { BitmapFactory.decodeStream(it) }
                    } else {
                        BitmapFactory.decodeFile(wallpaperUri)
                    }
                } else {
                    val asset = Appearance.builtinAsset(wallpaperKind)
                    if (asset != null) {
                        ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                    } else null
                }
            }.getOrNull()
        }
        if (bitmap != null) {
            Appearance.applyColors(bitmap, isDark)
            WallpaperState.set(ctx, bitmap, isDark, windowWidthPx)
        } else {
            Appearance.fallback(isDark)
            WallpaperState.clear()
        }
        wallpaperVersion++
    }

    @Suppress("UNUSED_EXPRESSION")
    val version = wallpaperVersion

    val r = Appearance.radius
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape((r - 12f).coerceAtLeast(6f).dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape((r - 10f).coerceAtLeast(8f).dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(r.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(r.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(r.dp)
    )

    MaterialTheme(colorScheme = colorScheme, typography = Typography(), shapes = shapes) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .onSizeChanged { size -> windowWidthPx = size.width }
        ) {
            @Suppress("UNUSED_EXPRESSION")
            version
            val glassBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop { drawContent() }
            val wallpaperBitmap = WallpaperState.original
            // 壁纸层单独放进 backdrop：卡片是这层之上的内容，不会采样到自己
            Box(Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                if (wallpaperBitmap != null) {
                    Image(
                        bitmap = wallpaperBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                val dim = Appearance.wallpaperDim
                if (dim > 0) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = dim / 100f * 0.75f))
                    )
                }
            }
            GlassBackdrop.instance = glassBackdrop
            content()
        }
    }
}
/** 系统手势条（小白条）占的高度，用于把内容与 Dock 一起抬到它上面 */
@Composable
fun navBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
