package com.k100pm.tuner.v4a2.utils

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局崩溃 / ANR 捕获器。
 *
 * 目的：把用户遇到的“闪退”真正记录到 viper.log，方便离线排查。
 * 覆盖两类：
 *   1. 未捕获异常（UncaughtException）→ 完整堆栈同步落盘
 *   2. ANR（主线程长时间无响应）→ 主线程堆栈同步落盘
 *
 * 局限：native(驱动)/OOM/被系统杀死 无法由本类捕获（需系统 dropbox/logcat 或第三方上报）。
 */
object CrashLogger {
    private const val TAG = "CrashLogger"

    /** 安装幂等标志，避免多次注册覆盖系统处理器。 */
    private val installed = AtomicBoolean(false)

    /** 保存系统默认处理器，崩溃时写完日志后转交给它（保证真正结束进程）。 */
    private var prevUncaught: Thread.UncaughtExceptionHandler? = null

    /** ANR 轮询间隔。 */
    private const val ANR_POLL_MS = 5_000L

    /** ANR 日志节流间隔。 */
    private const val ANR_LOG_COOLDOWN_MS = 10_000L

    private var lastAnrLogAt = 0L

    /**
     * 安装崩溃捕获（幂等）。需在进程最早入口调用一次，例如 MainViewModel.init / ViperService.onCreate。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        // 确保 FileLogger 已初始化（有落盘路径），未 init 则补一次。
        if (FileLogger.getLogFile() == null) {
            FileLogger.init(context)
        }

        // —— 未捕获异常：崩溃瞬间同步写堆栈到 viper.log ——
        prevUncaught = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.logSync("ERROR", "CRASH", "Uncaught on thread=[${thread.name}]")
                Log.getStackTraceString(throwable).split('\n').forEach { line ->
                    FileLogger.logSync("ERROR", "CRASH", line)
                }
                dumpThreads()
                FileLogger.logSync("INFO", "CRASH", "--- crash logged, terminating ---")
            } catch (_: Exception) {
            }
            prevUncaught?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }

        // —— ANR（主线程卡死）检测 ——
        startAnrWatchdog()
        FileLogger.logSync("INFO", "CRASH", "CrashLogger installed")
    }

    private fun startAnrWatchdog() {
        val mainThread = Looper.getMainLooper().thread
        Thread(null, {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(ANR_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val st = mainThread.state
                // 主线程持续处于等待/阻塞且超阈值，提示可能是卡死点。
                if (st == Thread.State.BLOCKED || st == Thread.State.WAITING) {
                    recordMainStack(mainThread)
                }
            }
        }, "ANR-Watchdog").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun recordMainStack(mainThread: Thread) {
        val now = System.currentTimeMillis()
        if (now - lastAnrLogAt < ANR_LOG_COOLDOWN_MS) return
        lastAnrLogAt = now
        FileLogger.logSync("WARN", "ANR-WATCH", "main thread state=${mainThread.state}")
        mainThread.stackTrace.take(20).forEach { e ->
            FileLogger.logSync("WARN", "ANR-WATCH", "\tat $e")
        }
    }

    private fun dumpThreads() {
        try {
            val liveness = Thread.getAllStackTraces()
            FileLogger.logSync("INFO", "CRASH", "--- thread dump (${liveness.size}) ---")
            for ((t, frames) in liveness) {
                if (t === Thread.currentThread()) continue
                FileLogger.logSync("INFO", "CRASH", "${t.name}(${t.state})")
                frames.take(6).forEach { f -> FileLogger.logSync("INFO", "CRASH", "\tat $f") }
            }
        } catch (_: Exception) {
        }
    }
}