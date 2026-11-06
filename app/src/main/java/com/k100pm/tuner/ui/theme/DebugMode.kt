package com.k100pm.tuner.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 隐藏调试开关（仅用于无设备时的 UI 预览）。
 *
 * 打开方式：设置 → 关于 → 连续点「版本」5 次 → 输入口令。
 * 作用范围：只把「可编辑」判定强制为 true，让各页 UI 全部展开；
 *          不会伪造模块状态，也不会真的往 DSP 写参数。
 */
object DebugMode {
    private const val PREFS = "debug_ui"
    private const val KEY = "ui_unlock"

    /** 解锁口令 */
    const val PASS = "016cmd"

    /** 连点次数 */
    const val TAPS = 5

    val state = MutableStateFlow(false)
    private var loaded = false

    fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        state.value = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    fun on(ctx: Context): Boolean {
        ensure(ctx)
        return state.value
    }

    fun set(ctx: Context, on: Boolean) {
        loaded = true
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        state.value = on
    }
}