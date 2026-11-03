package com.k100pm.tuner.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机写回：把「保存到文件」的 GEQ 曲线重新写进 Dolby DAP。
 *
 * 触发时机：
 *   BOOT_COMPLETED       — 开机完成
 *   MY_PACKAGE_REPLACED  — APP 覆盖升级后
 *
 * 为什么要重试：开机时 Dolby 的 effect 不一定已经初始化完，
 * 打不开就隔一会儿再试，最多 10 次（约 20 秒），失败就放弃，不无限重试。
 */
class DapBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACCEPTED_ACTIONS) return

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val curve = DapCurveStore.load(appContext) ?: return@Thread
                if (!DapCurveStore.bootApplyEnabled(appContext)) return@Thread

                repeat(6) { attempt ->
                    val err = runCatching { DapRealtime.writeCurve(curve, null) }.getOrNull()
                    if (err == null) {
                        runCatching { DapRealtime.save() }
                        return@Thread
                    }
                    // 广播的异步窗口一般只有 10 秒，总时长必须压在 8 秒内
                    if (attempt < 5) Thread.sleep(1_200L)
                }
            } catch (_: Throwable) {
                // 开机写回失败不影响系统，交给 Dolby 自己的数据库兜底
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}