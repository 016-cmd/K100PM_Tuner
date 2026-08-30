# K100PM Tuner ProGuard Rules

# ── libxposed API（必须保留，LSPosed 通过类名识别）──
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.XposedProvider { *; }
-keep class com.k100pm.tuner.hook.** { *; }

# ── Kotlin ──
-keepattributes *Annotation*
-keepattributes InnerClasses
-keep class kotlin.Metadata { *; }

# ── Compose ──
-dontwarn androidx.compose.**

# ── 保留 manifest 中的 xposed 相关属性不被 AGP 优化吃掉 ──
-keepnames class * {
    @androidx.annotation.Keep *;
}