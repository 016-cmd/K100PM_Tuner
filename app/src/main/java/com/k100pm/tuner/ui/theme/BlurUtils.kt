package com.k100pm.tuner.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.os.Build

/**
 * 模糊工具 — RenderScript 原生高斯模糊,fallback 缩略模糊.
 */
object BlurUtils {
    private var rs: RenderScript? = null

    fun blur(ctx: Context, src: Bitmap, radius: Float = 8f): Bitmap {
        // 先缩到小尺寸提速
        val ratio = 192f / src.width
        val smallW = 192
        val smallH = (src.height * ratio).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)

        return try {
            rsBlur(ctx, small, radius)
        } catch (_: Exception) {
            // RenderScript 不可用时 fallback:缩小再放大模拟模糊
            val tiny = Bitmap.createScaledBitmap(small, smallW / 4, smallH / 4, true)
            Bitmap.createScaledBitmap(tiny, smallW, smallH, true).also { tiny.recycle() }
        }
    }

    private fun rsBlur(ctx: Context, src: Bitmap, radius: Float): Bitmap {
        if (rs == null) {
            rs = if (Build.VERSION.SDK_INT >= 31) {
                RenderScript.create(ctx)
            } else {
                @Suppress("DEPRECATION")
                RenderScript.create(ctx)
            }
        }

        val input = Allocation.createFromBitmap(rs, src)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

        script.setRadius(Math.min(radius, 25f))
        script.setInput(input)
        script.forEach(output)
        output.copyTo(src)

        input.destroy()
        output.destroy()
        script.destroy()

        return src
    }
}