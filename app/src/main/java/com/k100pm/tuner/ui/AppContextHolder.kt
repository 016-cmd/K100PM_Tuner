package com.k100pm.tuner.ui

import android.content.Context

/**
 * 全局 Application Context 持有者.
 * 在 MainActivity.onCreate 中初始化.
 */
object AppContextHolder {
    lateinit var ctx: Context
}