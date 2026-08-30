package com.k100pm.tuner.v4a2.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object FileLogger {
    private const val TAG = "ViPER4Android"
    private const val MAX_FILE_SIZE = 2L * 1024 * 1024
    private const val LOG_FILE_NAME = "viper.log"
    private const val OLD_LOG_FILE_NAME = "viper.old.log"

    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "FileLogger").apply { isDaemon = true }
        }
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var outputStream: FileOutputStream? = null

    /** 每次进程只轮转一次（进程级去重），避免同一进程多次 init 误滚掉本次日志。 */
    @Volatile
    private var rotatedThisProcess = false

    @Volatile
    private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    fun init(context: Context) {
        val dir = File(context.filesDir, "Log")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, LOG_FILE_NAME)
        // 进程首次启动：把上次运行的当前日志滚动为“上次”，并删除更旧的（最多保留两份）。
        if (!rotatedThisProcess) {
            rotatedThisProcess = true
            val oldFile = File(dir, OLD_LOG_FILE_NAME)
            if (oldFile.exists()) oldFile.delete()        // 上上次 → 删除
            val cur = File(dir, LOG_FILE_NAME)
            if (cur.exists()) cur.renameTo(oldFile)       // 上次运行的 → 变为“上次”
        }
        openLogFile()
    }

    private fun openLogFile() {
        val file = logFile ?: return
        if (!file.exists()) file.createNewFile()
        outputStream = FileOutputStream(file, true)
    }

    /**
     * 运行中若当前日志超限：不再改名成 old（否则会破坏“最多两份”），
     * 而是直接清空当前文件续写，保持磁盘上始终只有 当前 + 上次 两份。
     */
    private fun rotateIfNeeded() {
        val file = logFile ?: return
        if (file.length() <= MAX_FILE_SIZE) return
        outputStream?.close()
        file.delete()                       // 清空当前日志（不额外生成历史文件）
        openLogFile()
    }

    private fun writeRaw(line: String) {
        executor.execute {
            try {
                rotateIfNeeded()
                outputStream?.write(line.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (_: Exception) {
            }
            listener?.invoke(line.trimEnd('\n'))
        }
    }

    private fun log(
        level: String,
        category: String,
        message: String,
    ) {
        val timestamp = dateFormatter.format(Date())
        writeRaw("$timestamp [$category][$level] $message\n")
    }

    fun d(
        category: String,
        message: String,
    ) {
        Log.d(TAG, message)
        log("DEBUG", category, message)
    }

    fun i(
        category: String,
        message: String,
    ) {
        Log.i(TAG, message)
        log("INFO", category, message)
    }

    fun w(
        category: String,
        message: String,
    ) {
        Log.w(TAG, message)
        log("WARN", category, message)
    }

    fun e(
        category: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
            log("ERROR", category, "$message: ${throwable.message}")
        } else {
            Log.e(TAG, message)
            log("ERROR", category, message)
        }
    }

    fun clearLogs() {
        executor.execute {
            try {
                outputStream?.close()
                outputStream = null
                val file = logFile ?: return@execute
                val oldFile = File(file.parentFile, OLD_LOG_FILE_NAME)
                if (oldFile.exists()) oldFile.delete()
                if (file.exists()) file.delete()
                openLogFile()
            } catch (_: Exception) {
            }
        }
    }

    fun getLogFile(): File? = logFile

    /**
     * 同步写日志（崩溃/ANR 专用）。
     * 崩溃瞬间异步线程大概率来不及执行，需在主线程同步落盘，确保闪退堆栈不丢失。
     */
    fun logSync(
        level: String,
        category: String,
        message: String,
    ) {
        try {
            val file = logFile ?: return
            if (!file.exists()) file.createNewFile()
            val ts = dateFormatter.format(Date())
            FileOutputStream(file, true).use { os ->
                os.write("$ts [$category][$level] $message\n".toByteArray(Charsets.UTF_8))
                os.flush()
                os.fd.sync()
            }
        } catch (_: Exception) {
        }
    }
}