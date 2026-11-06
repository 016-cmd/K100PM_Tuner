package com.k100pm.tuner.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 外观参数 + 壁纸取色（与 DolbyTuner 完全同一套参数）。
 *
 * 设计原则：
 *  - 参数全部落在 SharedPreferences，Compose 里读 state 直接重组
 *  - 数值型尽量用 Int（0~100 / 角度 / 档位），减少枚举兼容坑
 *  - 卡片、dock、设置卡共用同一套参数 → 视觉统一
 */
object Appearance {

    private const val PREFS = "k100pm_tuner"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 总开关 ──
    var enabled by mutableStateOf(true); private set

    // ── 玻璃 ──
    /** 折射强度 0~100 */
    var refraction by mutableIntStateOf(60); private set
    /** 折射边缘宽度：0 窄 / 1 标准 / 2 宽 */
    var edgeWidth by mutableIntStateOf(1); private set
    /** 模糊 0~40 dp */
    var blurRadius by mutableIntStateOf(16); private set
    /** 提亮 0~100 */
    var vibrancy by mutableIntStateOf(55); private set

    // ── 颜色 ──
    /** 色散 0~100（0 = 关） */
    var dispersion by mutableIntStateOf(35); private set
    /** 光源角度 0~360 */
    var lightAngle by mutableIntStateOf(315); private set
    /** 体积光：0 关 / 1 弱 / 2 标准 / 3 强 */
    var volumeLight by mutableIntStateOf(2); private set
    /** 卡片底色：0 自动 / 1 纯白 / 2 纯黑 */
    var tintMode by mutableIntStateOf(0); private set

    // ── 形状 ──
    /** 圆角 12~34 dp */
    var radius by mutableFloatStateOf(26f); private set
    /** 玻璃厚度：0 薄 / 1 标准 / 2 厚 */
    var thickness by mutableIntStateOf(1); private set

    // ── 动效 ──
    /** 果冻按压 0~100（0 = 关） */
    var jelly by mutableIntStateOf(70); private set
    /** 弹簧：0 软 / 1 标准 / 2 硬 */
    var spring by mutableIntStateOf(1); private set
    /** 光照互通 */
    var crossLight by mutableStateOf(true); private set
    /** 性能：0 自动 / 1 高画质 / 2 省电 */
    var perfMode by mutableIntStateOf(0); private set
    /** 卡片真折射 */
    var cardLiquid by mutableStateOf(true); private set

    // ── 页面 ──
    var animPage by mutableIntStateOf(1); private set
    var animCard by mutableIntStateOf(1); private set
    var animLaunch by mutableIntStateOf(1); private set

    // ── 壁纸 ──
    var wallpaperKind by mutableIntStateOf(1); private set
    var wallpaperDim by mutableIntStateOf(0); private set

    /** 壁纸平均亮度 */
    var wallLum by mutableFloatStateOf(0.5f); private set

    // ── 取色结果 ──
    var accent by mutableStateOf(Color(0xFF22C55E)); private set
    var secondary by mutableStateOf(Color(0xFF3B82F6)); private set
    var tertiary by mutableStateOf(Color(0xFFA855F7)); private set
    var colorBass by mutableStateOf(Color(0xFFF97316)); private set
    var colorVocal by mutableStateOf(Color(0xFF06B6D4)); private set
    var colorDyn by mutableStateOf(Color(0xFFA855F7)); private set
    var colorLimit by mutableStateOf(Color(0xFFF59E0B)); private set

    /** 可读性：0 默认 / 1 增强 */
    var readLevel by mutableIntStateOf(0); private set

    // ── 存取 ──
    fun load(ctx: Context) {
        val s = sp(ctx)
        // 旧版本这里存的是 Int（0/1），直接 getBoolean 会 ClassCastException → 闪退
        fun b(k: String, d: Boolean): Boolean = try {
            s.getBoolean(k, d)
        } catch (_: ClassCastException) {
            try { s.getInt(k, if (d) 1 else 0) != 0 } catch (_: Exception) { d }
        }
        fun i(k: String, d: Int): Int = try {
            s.getInt(k, d)
        } catch (_: ClassCastException) {
            try { if (s.getBoolean(k, d != 0)) 1 else 0 } catch (_: Exception) { d }
        }
        fun f(k: String, d: Float): Float = try {
            s.getFloat(k, d)
        } catch (_: ClassCastException) {
            try { s.getInt(k, d.toInt()).toFloat() } catch (_: Exception) { d }
        }

        enabled = b("ap_enabled", true)
        refraction = i("ap_refraction", 60).coerceIn(0, 100)
        edgeWidth = i("ap_edge_width", 1).coerceIn(0, 2)
        blurRadius = i("ap_blur_radius", 16).coerceIn(0, 40)
        vibrancy = i("ap_vibrancy", 55).coerceIn(0, 100)
        dispersion = i("ap_dispersion", 35).coerceIn(0, 100)
        lightAngle = i("ap_light_angle", 315).coerceIn(0, 360)
        volumeLight = i("ap_volume_light", 2).coerceIn(0, 3)
        tintMode = i("ap_tint", 0).coerceIn(0, 2)
        radius = f("ap_radius", 26f).coerceIn(12f, 34f)
        thickness = i("ap_thickness", 1).coerceIn(0, 2)
        jelly = i("ap_jelly", 70).coerceIn(0, 100)
        spring = i("ap_spring", 1).coerceIn(0, 2)
        crossLight = b("ap_cross_light", true)
        perfMode = i("ap_perf_mode", 0).coerceIn(0, 2)
        cardLiquid = b("ap_card_liquid", true)
        animPage = if (b("ap_anim_page", true)) 1 else 0
        animCard = if (b("ap_anim_card", true)) 1 else 0
        animLaunch = if (b("ap_anim_launch", true)) 1 else 0
        wallpaperKind = i("wp_kind", 1).coerceIn(0, 3)
        wallpaperDim = i("ap_wpdim", 0).coerceIn(0, 60)
        readLevel = i("ap_read", 0).coerceIn(0, 1)
        if (s.contains("pal_accent")) accent = Color(i("pal_accent", 0xFF22C55E.toInt()))
        if (s.contains("pal_secondary")) secondary = Color(i("pal_secondary", 0xFF3B82F6.toInt()))
        if (s.contains("pal_tertiary")) tertiary = Color(i("pal_tertiary", 0xFFA855F7.toInt()))
        if (s.contains("pal_bass")) colorBass = Color(i("pal_bass", 0xFFF97316.toInt()))
        if (s.contains("pal_vocal")) colorVocal = Color(i("pal_vocal", 0xFF06B6D4.toInt()))
        if (s.contains("pal_dyn")) colorDyn = Color(i("pal_dyn", 0xFFA855F7.toInt()))
        if (s.contains("pal_limit")) colorLimit = Color(i("pal_limit", 0xFFF59E0B.toInt()))
    }

    private fun putI(ctx: Context, key: String, v: Int, set: (Int) -> Unit) {
        set(v); sp(ctx).edit().putInt(key, v).apply()
    }

    private fun putB(ctx: Context, key: String, v: Boolean, set: (Boolean) -> Unit) {
        set(v); sp(ctx).edit().putBoolean(key, v).apply()
    }

    fun setEnabled(ctx: Context, v: Boolean) = putB(ctx, "ap_enabled", v) { enabled = it }
    fun setRefraction(ctx: Context, v: Int) = putI(ctx, "ap_refraction", v) { refraction = it }
    fun setEdgeWidth(ctx: Context, v: Int) = putI(ctx, "ap_edge_width", v) { edgeWidth = it }
    fun setBlurRadius(ctx: Context, v: Int) = putI(ctx, "ap_blur_radius", v) { blurRadius = it }
    fun setVibrancy(ctx: Context, v: Int) = putI(ctx, "ap_vibrancy", v) { vibrancy = it }
    fun setDispersion(ctx: Context, v: Int) = putI(ctx, "ap_dispersion", v) { dispersion = it }
    fun setLightAngle(ctx: Context, v: Int) = putI(ctx, "ap_light_angle", v) { lightAngle = it }
    fun setVolumeLight(ctx: Context, v: Int) = putI(ctx, "ap_volume_light", v) { volumeLight = it }
    fun setTintMode(ctx: Context, v: Int) = putI(ctx, "ap_tint", v) { tintMode = it }
    fun setRadius(ctx: Context, v: Float) {
        radius = v; sp(ctx).edit().putFloat("ap_radius", v).apply()
    }
    fun setThickness(ctx: Context, v: Int) = putI(ctx, "ap_thickness", v) { thickness = it }
    fun setJelly(ctx: Context, v: Int) = putI(ctx, "ap_jelly", v) { jelly = it }
    fun setSpring(ctx: Context, v: Int) = putI(ctx, "ap_spring", v) { spring = it }
    fun setCrossLight(ctx: Context, v: Boolean) = putB(ctx, "ap_cross_light", v) { crossLight = it }
    fun setPerfMode(ctx: Context, v: Int) = putI(ctx, "ap_perf_mode", v) { perfMode = it }
    fun setCardLiquid(ctx: Context, v: Boolean) = putB(ctx, "ap_card_liquid", v) { cardLiquid = it }
    fun setAnimPage(ctx: Context, on: Boolean) {
        animPage = if (on) 1 else 0
        sp(ctx).edit().putBoolean("ap_anim_page", on).apply()
    }
    fun setAnimCard(ctx: Context, on: Boolean) {
        animCard = if (on) 1 else 0
        sp(ctx).edit().putBoolean("ap_anim_card", on).apply()
    }
    fun setAnimLaunch(ctx: Context, on: Boolean) {
        animLaunch = if (on) 1 else 0
        sp(ctx).edit().putBoolean("ap_anim_launch", on).apply()
    }
    fun setWallpaperKind(ctx: Context, v: Int) = putI(ctx, "wp_kind", v) { wallpaperKind = it }
    fun setWallpaperDim(ctx: Context, v: Int) = putI(ctx, "ap_wpdim", v) { wallpaperDim = it }
    fun setReadLevel(ctx: Context, v: Int) = putI(ctx, "ap_read", v.coerceIn(0, 1)) { readLevel = it }

    // ── 预设 ──
    /** 玻璃风格预设（一次写入一组参数）0 清透 / 1 标准 / 2 浓厚 / 3 柔和 / 4 省电 */
    fun applyPreset(ctx: Context, i: Int) {
        when (i) {
            0 -> { // 清透：模糊很小、折射大、色散明显
                setRefraction(ctx, 85); setEdgeWidth(ctx, 1); setBlurRadius(ctx, 6)
                setVibrancy(ctx, 70); setDispersion(ctx, 55); setLightAngle(ctx, 315)
                setVolumeLight(ctx, 2); setRadius(ctx, 26f); setThickness(ctx, 1)
            }
            1 -> { // 标准
                setRefraction(ctx, 60); setEdgeWidth(ctx, 1); setBlurRadius(ctx, 16)
                setVibrancy(ctx, 55); setDispersion(ctx, 35); setLightAngle(ctx, 315)
                setVolumeLight(ctx, 2); setRadius(ctx, 26f); setThickness(ctx, 1)
            }
            2 -> { // 浓厚
                setRefraction(ctx, 70); setEdgeWidth(ctx, 2); setBlurRadius(ctx, 30)
                setVibrancy(ctx, 65); setDispersion(ctx, 30); setLightAngle(ctx, 315)
                setVolumeLight(ctx, 3); setRadius(ctx, 30f); setThickness(ctx, 2)
            }
            3 -> { // 柔和
                setRefraction(ctx, 45); setEdgeWidth(ctx, 0); setBlurRadius(ctx, 10)
                setVibrancy(ctx, 50); setDispersion(ctx, 20); setLightAngle(ctx, 315)
                setVolumeLight(ctx, 1); setRadius(ctx, 26f); setThickness(ctx, 0)
            }
            else -> { // 省电
                setRefraction(ctx, 20); setEdgeWidth(ctx, 0); setBlurRadius(ctx, 0)
                setVibrancy(ctx, 30); setDispersion(ctx, 0); setLightAngle(ctx, 315)
                setVolumeLight(ctx, 0); setRadius(ctx, 22f); setThickness(ctx, 0)
            }
        }
    }

    /** 当前参数最接近哪个预设（给按钮高亮用） */
    fun presetIndex(): Int {
        val r = refraction
        return when {
            r >= 65 && blurRadius >= 24 -> 2
            r >= 75 -> 0
            r <= 30 -> 4
            r <= 50 -> 3
            else -> 1
        }
    }

    /** 兼容旧调用：等同 presetIndex() */
    val glass: Int get() = presetIndex()

    // ── 派生值 ──
    fun strokeDp(): Float = when (edgeWidth) {
        0 -> 0.9f
        2 -> 2.4f
        else -> 1.5f
    }

    /** 卡片底色透明度 */
    fun tintAlpha(isDark: Boolean): Float {
        val base = when (tintMode) {
            1 -> if (isDark) 0.34f else 0.62f   // 纯白
            2 -> if (isDark) 0.62f else 0.34f   // 纯黑
            else -> if (isDark) 0.20f else 0.30f
        }
        val thick = when (thickness) { 0 -> 0.88f; 2 -> 1.12f; else -> 1f }
        val boost = if (readLevel >= 1) 0.16f else 0f
        return (base * thick + boost).coerceIn(0.12f, 0.92f)
    }

    fun tintColor(isDark: Boolean): Color = when (tintMode) {
        1 -> Color.White
        2 -> Color.Black
        else -> if (isDark) Color.Black else Color.White
    }

    fun glassFill(isDark: Boolean): Color =
        tintColor(isDark).copy(alpha = tintAlpha(isDark).coerceAtLeast(0.18f))

    val cardAlphaDark: Float get() = tintAlpha(true)
    val cardAlphaLight: Float get() = tintAlpha(false)

    // ── 壁纸取色 ──
    fun applyColors(bitmap: Bitmap?, dark: Boolean) {
        if (bitmap == null) { fallback(dark); return }
        cachePalette(null, computePalette(bitmap, dark))
    }

    fun computePalette(src: Bitmap, dark: Boolean): List<Color> {
        val small = runCatching { Bitmap.createScaledBitmap(src, 48, 48, true) }.getOrNull() ?: src
        val buckets = HashMap<Int, FloatArray>()
        val hsv = FloatArray(3)
        var lumSum = 0.0
        var lumCount = 0
        for (y in 0 until small.height step 2) {
            for (x in 0 until small.width step 2) {
                val px = small.getPixel(x, y)
                val r = android.graphics.Color.red(px)
                val g = android.graphics.Color.green(px)
                val b = android.graphics.Color.blue(px)
                lumSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                lumCount++
                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] < 0.18f || hsv[2] < 0.16f || hsv[2] > 0.94f) continue
                val w = (hsv[1] * 0.7f + 0.3f) * (1f - kotlin.math.abs(hsv[2] - 0.55f))
                if (w <= 0f) continue
                val key = (hsv[0] / 15f).toInt().coerceIn(0, 23)
                val c = buckets.getOrPut(key) { FloatArray(4) }
                c[0] += w; c[1] += r * w; c[2] += g * w; c[3] += b * w
            }
        }
        if (small !== src) runCatching { small.recycle() }
        wallLum = (lumSum / lumCount.coerceAtLeast(1)).toFloat()

        val sorted = buckets.entries.sortedByDescending { it.value[0] }.take(3)
        val out = ArrayList<Color>()
        sorted.forEach { e ->
            val w = e.value[0].coerceAtLeast(0.0001f)
            out.add(
                tuneColor(
                    Color(
                        (e.value[1] / w).toInt().coerceIn(0, 255),
                        (e.value[2] / w).toInt().coerceIn(0, 255),
                        (e.value[3] / w).toInt().coerceIn(0, 255)
                    ), dark
                )
            )
        }
        // 纯黑/纯白/低饱和壁纸会被全部过滤 → 必须先兜底，否则 [0] 越界闪退
        if (out.isEmpty()) out.add(if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        while (out.size < 3) out.add(shiftHue(out[0], 40f * out.size))
        if (hueDiff(out[0], out[1]) < 40f) out[1] = shiftHue(out[0], 45f)
        if (hueDiff(out[0], out[2]) < 60f) out[2] = shiftHue(out[0], 120f)
        return out
    }

    fun cachePalette(ctx: Context?, pal: List<Color>) {
        accent = pal[0]
        secondary = pal[1]
        tertiary = pal[2]
        colorBass = shiftHue(pal[0], -30f)
        colorVocal = shiftHue(pal[0], 150f)
        colorDyn = shiftHue(pal[0], 80f)
        colorLimit = shiftHue(pal[0], 200f)
        if (ctx != null) {
            sp(ctx).edit()
                .putInt("pal_accent", android.graphics.Color.rgb(
                    (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt()))
                .putInt("pal_secondary", android.graphics.Color.rgb(
                    (secondary.red * 255).toInt(), (secondary.green * 255).toInt(), (secondary.blue * 255).toInt()))
                .putInt("pal_tertiary", android.graphics.Color.rgb(
                    (tertiary.red * 255).toInt(), (tertiary.green * 255).toInt(), (tertiary.blue * 255).toInt()))
                .putInt("pal_bass", android.graphics.Color.rgb(
                    (colorBass.red * 255).toInt(), (colorBass.green * 255).toInt(), (colorBass.blue * 255).toInt()))
                .putInt("pal_vocal", android.graphics.Color.rgb(
                    (colorVocal.red * 255).toInt(), (colorVocal.green * 255).toInt(), (colorVocal.blue * 255).toInt()))
                .putInt("pal_dyn", android.graphics.Color.rgb(
                    (colorDyn.red * 255).toInt(), (colorDyn.green * 255).toInt(), (colorDyn.blue * 255).toInt()))
                .putInt("pal_limit", android.graphics.Color.rgb(
                    (colorLimit.red * 255).toInt(), (colorLimit.green * 255).toInt(), (colorLimit.blue * 255).toInt()))
                .apply()
        }
    }

    fun fallback(dark: Boolean) {
        accent = if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        secondary = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        tertiary = if (dark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        colorBass = Color(0xFFF97316)
        colorVocal = Color(0xFF06B6D4)
        colorDyn = Color(0xFFA855F7)
        colorLimit = Color(0xFFF59E0B)
        wallLum = if (dark) 0.12f else 0.92f
    }

    // ── 内置壁纸 ──
    fun builtinAsset(kind: Int): String? = when (kind) {
        1 -> "wp_zzz.jpg"
        2 -> "wp_sea.jpg"
        3 -> "wp_buzz.jpg"
        else -> null
    }

    fun builtinName(kind: Int): String = when (kind) {
        1 -> "绝区零"
        2 -> "海风"
        3 -> "微醺"
        else -> "本地图片"
    }

    private fun hueOf(c: Color): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (c.red * 255).toInt().coerceIn(0, 255),
            (c.green * 255).toInt().coerceIn(0, 255),
            (c.blue * 255).toInt().coerceIn(0, 255), hsv
        )
        return hsv[0]
    }

    private fun hueDiff(a: Color, b: Color): Float {
        val d = kotlin.math.abs(hueOf(a) - hueOf(b))
        return kotlin.math.min(d, 360f - d)
    }

    private fun shiftHue(c: Color, deg: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (c.red * 255).toInt().coerceIn(0, 255),
            (c.green * 255).toInt().coerceIn(0, 255),
            (c.blue * 255).toInt().coerceIn(0, 255), hsv
        )
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun tuneColor(c: Color, dark: Boolean): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (c.red * 255).toInt().coerceIn(0, 255),
            (c.green * 255).toInt().coerceIn(0, 255),
            (c.blue * 255).toInt().coerceIn(0, 255), hsv
        )
        hsv[1] = hsv[1].coerceIn(0.50f, 0.72f)
        hsv[2] = if (dark) 0.80f else 0.55f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    // ── 动效派生 ──
    fun springDamping(): Float = when (spring) { 0 -> 0.72f; 2 -> 0.92f; else -> 0.82f }
    fun springStiffness(): Float = when (spring) { 0 -> 220f; 2 -> 480f; else -> 340f }
}

/**
 * 小字 / 副标题色。
 * 默认：跟主题但保证 alpha；增强：100% 不透明纯黑或纯白，按壁纸亮度反向。
 */
@Composable
fun subInk(alpha: Float = 0.86f): Color {
    val dark = WallpaperState.isDark
    return if (Appearance.readLevel >= 1) {
        if (dark) Color.White else Color.Black
    } else {
        val base = if (dark) Color(0xFFDCDCE4) else Color(0xFF3C3C46)
        base.copy(alpha = alpha.coerceAtLeast(0.80f))
    }
}

/** 主文字色：增强档给满不透明纯色 */
@Composable
fun mainInk(): Color {
    val dark = WallpaperState.isDark
    return if (Appearance.readLevel >= 1) {
        if (dark) Color.White else Color.Black
    } else {
        MaterialTheme.colorScheme.onSurface
    }
}