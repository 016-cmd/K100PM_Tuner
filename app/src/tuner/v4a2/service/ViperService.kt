package com.k100pm.tuner.v4a2.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.SparseArray
import com.k100pm.tuner.v4a2.audio.AudioDevice
import com.k100pm.tuner.v4a2.audio.AudioOutputDetector
import com.k100pm.tuner.v4a2.audio.AudioSessionMonitor
import com.k100pm.tuner.v4a2.data.ViperContainer
import com.k100pm.tuner.v4a2.data.model.DeviceSettings
import com.k100pm.tuner.v4a2.data.repository.ViperRepository
import com.k100pm.tuner.v4a2.effect.EffectState
import com.k100pm.tuner.v4a2.effect.deserializeEffectPrefs
import com.k100pm.tuner.v4a2.effect.serializeEffectPrefs
import com.k100pm.tuner.v4a2.utils.FileLogger
import com.k100pm.tuner.v4a2.utils.RootShell
import com.k100pm.tuner.v4a2.viper.ConfigChannel
import com.k100pm.tuner.v4a2.viper.ViperDispatcher
import com.k100pm.tuner.v4a2.viper.ViperParams
import com.k100pm.tuner.v4a2.viper.ViperEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * V4A 会话引擎（由模块/APP 用 am startservice 拉起，普通不可见 Service，无通知）。
 *
 * 由官方 2.0.3 `ViperService` 照搬改造而来：
 *  - 保留：session管理、设备感知/切换、master门控、applyState、AIDL(v5 SHM)写入、DDC/Convolver bulk。
 *  - 去掉：Hilt 注入、LifecycleService 前台通知（改为普通 Service + 模块拉起）。
 *  - repository 从 [ViperContainer] 手动获取（去 Hilt）。
 */
class ViperService : Service() {

    private val binder = LocalBinder()

    private val sessions = SparseArray<ViperEffect>()
    private var globalEffect: ViperEffect? = null

    /** true = AIDL（新版 v2.0.1 驱动 + SHM v5）；false = HIDL/legacy（逐参数） */
    private var useAidlTypeUuid: Boolean = true

    private var globalMode: Boolean = false
    private var audioOutputDetector: AudioOutputDetector? = null
    private var sessionMonitor: AudioSessionMonitor? = null
    private var bootMasterEnabled: Boolean = false
    private var configLoaded = false

    private var stateProvider: (() -> EffectState)? = null
    private var lastUiState: EffectState? = null
    private var lastBulkDdcKey: String? = null
    private var lastBulkConvolverKey: String? = null

    private val masterEnabled: Boolean
        get() = stateProvider?.invoke()?.masterEnable ?: lastUiState?.masterEnable ?: bootMasterEnabled

    private val repository: ViperRepository
        get() = ViperContainer.repository()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    inner class LocalBinder : Binder() {
        val service: ViperService get() = this@ViperService
    }

    // ---------------- 生命周期(模块拉起) ----------------

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志：落盘到 files/Log/viper.log（此前从未 init，导致日志只进 logcat、不落盘）。
        FileLogger.init(applicationContext)
        // 安装全局崩溃/ANR捕获（幂等）：避免用户在后台/开机场景的闪退无从记录。
        com.k100pm.tuner.v4a2.utils.CrashLogger.install(applicationContext)
        FileLogger.i("ViperService", "Service created")
        scope.launch { ensureConfigLoaded() }
    }

    override fun onDestroy() {
        stopSessionMonitor()
        audioOutputDetector?.stop()
        audioOutputDetector = null
        globalEffect?.let { it.enabled = false; it.release() }
        globalEffect = null
        releaseAllSessions()
        scope.cancel()
        FileLogger.i("ViperService", "Service destroyed")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 模块 am startservice 拉起时 intent 通常不带 action（null）→ 也走开机恢复写回。
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                // 开机（模块拉起）：恢复调音写回驱动 → 写完即关（不静默常驻）。
                // APP 打开用 bindService（绑定），走 onBind；这里仅处理模块 am startservice 的开机链路。
                scope.launch {
                    ensureConfigLoaded()
                    // 开机模块联动：若用户关闭 auto_start，则不恢复调音、直接关闭（不常驻）
                    val linkageOnBoot = repository.getBooleanPreference(ViperRepository.PREF_AUTO_START, true).first()
                    if (!linkageOnBoot) {
                        FileLogger.i("ViperService", "Module linkage on boot disabled, closing service")
                        stopSelf()
                        return@launch
                    }
                    if (masterEnabled) {
                        val state = ViperDispatcher.loadFullStateFromPrefs(repository)
                        applyState(state, true)
                    }
                    FileLogger.i("ViperService", "Boot restore written, closing service")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                releaseAllSessions()
                globalEffect?.let { it.enabled = false; it.release() }
                globalEffect = null
                stopSelf()
            }
            ACTION_TOGGLE_MASTER -> {
                val next = intent?.getBooleanExtra(EXTRA_MASTER_ENABLED, false) ?: false
                scope.launch {
                    ensureConfigLoaded()
                    bootMasterEnabled = next
                    val state = ViperDispatcher.loadFullStateFromPrefs(repository)
                    stateProvider = null
                    lastUiState = state
                    applyState(state, next)
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- 配置 ----------------

    private suspend fun ensureConfigLoaded() {
        if (configLoaded) return
        useAidlTypeUuid = repository.aidlMode
        // 全局模式会把 V4A 挂到主输出路径，挤掉杜比（Dolby）处理，永久禁用：
        // 此处强制 globalMode=false，并顺带清理历史残留的 true 设置，杜绝全局面开启。
        globalMode = false
        if (repository.getBooleanPreference(ViperRepository.PREF_GLOBAL_MODE).first() != false) {
            repository.setBooleanPreference(ViperRepository.PREF_GLOBAL_MODE, false)
        }
        bootMasterEnabled = repository.getBooleanPreference(ViperRepository.PREF_MASTER_ENABLE).first() ?: false
        ViperContainer.init(this)
        configLoaded = true
    }

    // ---------------- 全局 effect ----------------

    private fun initGlobalEffect() {
        val typeUuid =
            if (useAidlTypeUuid) ViperEffect.EFFECT_TYPE_UUID_AIDL else ViperEffect.EFFECT_TYPE_UUID
        val effect = ViperEffect(0, typeUuid)
        if (!effect.create()) {
            FileLogger.e("ViperService", "Failed to create global effect")
            return
        }
        globalEffect = effect
        FileLogger.i("ViperService", "Global effect created (aidlType=$useAidlTypeUuid)")
    }

    // ---------------- applyState (核心) ----------------

    private fun applyState(state: EffectState, masterOn: Boolean) {
        if (!masterOn) {
            stopSessionMonitor()
            releaseAllSessions()
            globalEffect?.let { it.enabled = false; it.release() }
            globalEffect = null
            return
        }
        if (globalMode) {
            if (globalEffect == null) initGlobalEffect()
        } else {
            if (sessionMonitor == null) startSessionMonitor()
        }
        var shmWritten = false
        globalEffect?.let { effect ->
            effect.enabled = true
            if (useAidlTypeUuid) {
                writeAidlFullState(state)
                shmWritten = true
            } else {
                applyFullStateHidl(effect, state, true)
            }
        }
        for (i in 0 until sessions.size()) {
            val effect = sessions.valueAt(i)
            effect.enabled = true
            if (useAidlTypeUuid) {
                if (!shmWritten) {
                    writeAidlFullState(state)
                    shmWritten = true
                }
            } else {
                applyFullStateHidl(effect, state, true)
            }
        }
    }

    private var currentServiceDeviceId: String = AudioDevice.ID_SPEAKER

    // ---------------- 设备感知/切换 ----------------

    private fun startAudioOutputMonitor() {
        val detector = AudioOutputDetector(this)
        audioOutputDetector = detector
        currentServiceDeviceId = detector.activeDevice.value.id
        scope.launch {
            detector.activeDevice.collect { device ->
                if (device.id != currentServiceDeviceId) {
                    FileLogger.i(
                        "ViperService",
                        "Device changed: $currentServiceDeviceId -> ${device.id} (${device.name})",
                    )
                    currentServiceDeviceId = device.id
                    reapplyForDevice(device)
                }
            }
        }
    }

    private suspend fun reapplyForDevice(device: AudioDevice) {
        val saved = repository.getDeviceSettings(device.id)
        val state: EffectState =
            if (saved != null) {
                FileLogger.i("ViperService", "Loading device settings from DB for ${device.id}")
                val baseState = ViperDispatcher.loadFullStateFromPrefs(repository)
                val json = JSONObject(saved.settingsJson)
                deserializeEffectPrefs(json, baseState).also {
                    repository.updateDeviceLastConnected(device.id)
                }
            } else {
                FileLogger.i("ViperService", "No DB entry for ${device.id}, using DataStore defaults")
                val s = ViperDispatcher.loadFullStateFromPrefs(repository)
                val json = serializeEffectPrefs(s)
                repository.saveDeviceSettings(
                    DeviceSettings(
                        deviceId = device.id,
                        deviceName = device.name,
                        isHeadphone = device.isHeadphone,
                        settingsJson = json.toString(),
                    ),
                )
                s
            }
        applyState(state, masterEnabled)
    }

    // ---------------- AIDL 写入 (v5 SHM) ----------------

    private fun writeAidlFullState(state: EffectState) {
        lastUiState = state
        ConfigChannel.writeFullState(state)
        if (state.ddc.enable && state.ddc.device.isNotEmpty()) {
            applyDdcDeviceAidl(state.ddc.device)
        }
        if (state.convolver.enable && state.convolver.kernelFile.isNotEmpty()) {
            applyConvolverKernelAidl(state.convolver.kernelFile)
        }
    }

    private fun republishLastStateOnAidl() {
        val state = stateProvider?.invoke() ?: lastUiState ?: return
        ConfigChannel.writeFullState(state)
    }

    // ---------------- HIDL (保留，非本路线主用) ----------------

    private fun applyFullStateHidl(effect: ViperEffect, state: EffectState, masterEnabled: Boolean) {
        ViperDispatcher.dispatchFullState(effect, state, masterEnabled)
    }

    // ---------------- Session 管理 ----------------

    private fun startSessionMonitor() {
        stopSessionMonitor()
        val monitor =
            AudioSessionMonitor(
                context = this,
                onSessionOpen = { sessionId, pkg -> openSession(sessionId, pkg) },
                onSessionClose = { sessionId -> closeSession(sessionId) },
            )
        monitor.start()
        sessionMonitor = monitor
    }

    private fun stopSessionMonitor() {
        sessionMonitor?.stop()
        sessionMonitor = null
    }

    private fun openSession(sessionId: Int, packageName: String) {
        if (!masterEnabled) return
        if (globalMode) return
        if (sessions.get(sessionId) != null) return
        val typeUuid =
            if (useAidlTypeUuid) ViperEffect.EFFECT_TYPE_UUID_AIDL else ViperEffect.EFFECT_TYPE_UUID
        val effect = ViperEffect(sessionId, typeUuid)
        if (!effect.create()) {
            FileLogger.e("ViperService", "Failed to create effect for session $sessionId")
            return
        }
        sessions.put(sessionId, effect)
        scope.launch { dispatchFullStateToEffect(effect) }
    }

    private fun closeSession(sessionId: Int) {
        val effect = sessions.get(sessionId) ?: return
        effect.enabled = false
        effect.release()
        sessions.remove(sessionId)
    }

    private fun releaseAllSessions() {
        for (i in 0 until sessions.size()) {
            val effect = sessions.valueAt(i)
            effect.enabled = false
            effect.release()
        }
        sessions.clear()
    }

    private suspend fun dispatchFullStateToEffect(effect: ViperEffect, skipShmWrite: Boolean = false) {
        val state = ViperDispatcher.loadFullStateFromPrefs(repository)
        val isMasterOn = masterEnabled
        effect.enabled = isMasterOn
        if (useAidlTypeUuid) {
            if (!skipShmWrite) writeAidlFullState(state)
            return
        }
        applyFullStateHidl(effect, state, isMasterOn)
    }

    // ---------------- DDC / Convolver (bulk) ----------------

    fun applyDdcDeviceAidl(name: String, force: Boolean = false) {
        if (name == lastBulkDdcKey && !force) return
        val ddcDir = File(getExternalFilesDir(null), "DDC")
        val file = File(ddcDir, "$name.vdc")
        if (!file.exists()) {
            FileLogger.w("ViperService", "DDC file missing: $name")
            return
        }
        val parsed = parseVdc(file) ?: return
        val sec44100 = parsed.first
        val sec48000 = parsed.second
        val perRateSize = sec44100.sumOf { it.size }
        val flat = FloatArray(perRateSize * 2)
        var off = 0
        for (sec in sec44100) {
            System.arraycopy(sec, 0, flat, off, sec.size)
            off += sec.size
        }
        for (sec in sec48000) {
            System.arraycopy(sec, 0, flat, off, sec.size)
            off += sec.size
        }
        ConfigChannel.writeBulkDdc(perRateSize, flat)
        lastBulkDdcKey = name
    }

    fun applyConvolverKernelAidl(fileName: String, force: Boolean = false) {
        if (fileName == lastBulkConvolverKey && !force) return
        // 官方思路：卷积生效与否由 enable(PARAM_CONVOLVER_ENABLE, 走 v5 params) 独立控制，
        // 关开关时 enable=0 即不生效，无需清空 bulk 路径/驱动残留。
        // 本方法只负责"写路径"：切换文件时覆盖 copy 新 IR + 写新路径，bulk 路径指向新文件即只加载新文件。
        if (fileName.isEmpty()) {
            lastBulkConvolverKey = null
            return
        }
        val src = File(File(getExternalFilesDir(null), "Kernel"), fileName)
        if (!src.exists()) {
            FileLogger.w("ViperService", "Kernel file missing: $fileName")
            return
        }
        try {
            // ① 覆盖式写入驱动真实路径（同一文件下 force copy 覆盖；不同文件则新文件就位，bulk 指向它）
            val destDir = "/data/local/tmp/v4a/kernel"
            val destPath = "$destDir/$fileName"
            // ② copy IR 到驱动真实路径（驱动 SetKernel(path) 要 open 这个文件）
            val copied = RootShell.copyFile(src, destPath)
            if (!copied) {
                FileLogger.e("ViperService", "Kernel copy to driver failed: $destPath")
                return
            }
            // ③ 把路径写进 bulk，驱动据此 SetKernel(path) 加载（cmd=2@2060 size@2064 path@2080，官方2.0.3协议）
            ConfigChannel.writeBulkConvolverPath(destPath)
            FileLogger.i("ViperService", "Kernel path(AIDL) written to bulk: $destPath")
            lastBulkConvolverKey = fileName
        } catch (e: Exception) {
            FileLogger.e("ViperService", "Failed to apply kernel(AIDL): $fileName", e)
        }
    }

    private fun parseVdc(file: File): Pair<List<FloatArray>, List<FloatArray>>? {
        try {
            var coeffs44100: FloatArray? = null
            var coeffs48000: FloatArray? = null
            for (line in file.readLines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("SR_44100:") -> {
                        coeffs44100 =
                            trimmed.removePrefix("SR_44100:").split(",").map { it.trim().toFloat() }.toFloatArray()
                    }
                    trimmed.startsWith("SR_48000:") -> {
                        coeffs48000 =
                            trimmed.removePrefix("SR_48000:").split(",").map { it.trim().toFloat() }.toFloatArray()
                    }
                }
            }
            val a = coeffs44100
            val b = coeffs48000
            if (a == null || b == null || a.isEmpty() || a.size != b.size || a.size % 5 != 0) {
                FileLogger.w("ViperService", "DDC coefficient parse failure: ${file.name}")
                return null
            }
            return a.toList().chunked(5).map { it.toFloatArray() } to
                b.toList().chunked(5).map { it.toFloatArray() }
        } catch (e: Exception) {
            FileLogger.e("ViperService", "Failed to parse DDC: ${file.name}", e)
            return null
        }
    }

    // ---------------- 对外方法(UI 使用) ----------------

    fun setStateProvider(provider: () -> EffectState) {
        stateProvider = provider
    }

    fun dispatchFullState(state: EffectState) {
        applyState(state, masterEnabled)
    }

    // ---------------- 补全官方 public API ----------------

    fun dispatchParam(
        param: Int,
        value: Int,
        republishAidl: Boolean = true,
    ) {
        if (useAidlTypeUuid) {
            if (republishAidl) republishLastStateOnAidl()
            return
        }
        globalEffect?.setParameter(param, value)
        for (i in 0 until sessions.size()) {
            sessions.valueAt(i).setParameter(param, value)
        }
    }

    fun dispatchParam(
        param: Int,
        val1: Int,
        val2: Int,
        val3: Int,
        republishAidl: Boolean = true,
    ) {
        if (useAidlTypeUuid) {
            if (republishAidl) republishLastStateOnAidl()
            return
        }
        globalEffect?.setParameter(param, val1, val2, val3)
        for (i in 0 until sessions.size()) {
            sessions.valueAt(i).setParameter(param, val1, val2, val3)
        }
    }

    fun dispatchParam(
        param: Int,
        value: ByteArray,
        republishAidl: Boolean = true,
    ) {
        if (useAidlTypeUuid && republishAidl) republishLastStateOnAidl()
        globalEffect?.setParameter(param, value)
        for (i in 0 until sessions.size()) {
            sessions.valueAt(i).setParameter(param, value)
        }
    }

    fun applyConvolverKernelHidl(
        fileName: String,
        effect: Void? = null,
    ) {
        if (fileName.isEmpty()) {
            dispatchParam(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, 0, 0, 1)
            return
        }
        val src = File(File(getExternalFilesDir(null), "Kernel"), fileName)
        if (!src.exists()) {
            FileLogger.w("ViperService", "Kernel file missing: $fileName")
            return
        }
        try {
            val decoded = com.k100pm.tuner.v4a2.utils.WavDecoder.decode(src.readBytes())
            val samples = decoded.samples
            val totalFloats = samples.size
            val channelCount = decoded.channels
            dispatchParam(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, totalFloats, channelCount, 0)
            val rawBytes =
                java.nio.ByteBuffer.allocate(totalFloats * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .also { for (f in samples) it.putFloat(f) }
                    .array()
            val crc = java.util.zip.CRC32().apply { update(rawBytes) }.value.toInt()
            val maxFloatsPerChunk = 2046
            var offset = 0
            var chunkIndex = 0
            while (offset < totalFloats) {
                val remaining = totalFloats - offset
                val floatsInChunk = minOf(remaining, maxFloatsPerChunk)
                val chunk = java.nio.ByteBuffer.allocate(8192).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                chunk.putInt(chunkIndex)
                chunk.putInt(floatsInChunk)
                chunk.put(rawBytes, offset * 4, floatsInChunk * 4)
                dispatchParam(ViperParams.PARAM_CONVOLVER_SET_BUFFER, chunk.array())
                offset += floatsInChunk
                chunkIndex++
            }
            val kernelId = fileName.hashCode()
            dispatchParam(ViperParams.PARAM_CONVOLVER_COMMIT_BUFFER, totalFloats, crc, kernelId)
            FileLogger.i("ViperService", "Kernel streamed: $fileName chunks=$chunkIndex")
        } catch (e: Exception) {
            FileLogger.e("ViperService", "Failed to stream kernel: $fileName", e)
        }
    }

    fun applyDdcDeviceHidl(
        name: String,
        effect: Void? = null,
    ) {
        val file = File(File(getExternalFilesDir(null), "DDC"), "$name.vdc")
        if (!file.exists()) {
            FileLogger.w("ViperService", "DDC file missing: $name")
            return
        }
        val parsed = parseVdc(file) ?: return
        val sec44100 = parsed.first
        val sec48000 = parsed.second
        val sectionCount = sec44100.size
        val floatsPerRate = sectionCount * 5
        val naturalSize = 4 + floatsPerRate * 4 * 2
        val wireSize =
            when {
                naturalSize <= 256 -> 256
                naturalSize <= 1024 -> 1024
                else -> {
                    FileLogger.w("ViperService", "DDC file too large")
                    return
                }
            }
        val bytes = ByteArray(wireSize)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(floatsPerRate)
        for (s in sec44100) for (v in s) buf.putFloat(v)
        for (s in sec48000) for (v in s) buf.putFloat(v)
        dispatchParam(ViperParams.PARAM_DDC_COEFFICIENTS, bytes)
    }

    fun getActiveEffect(): com.k100pm.tuner.v4a2.viper.ViperEffect? {
        globalEffect?.let { if (it.isCreated) return it }
        for (i in 0 until sessions.size()) {
            val effect = sessions.valueAt(i)
            if (effect.isCreated) return effect
        }
        return null
    }

    fun setGlobalMode(enabled: Boolean) {
        globalMode = enabled
        if (!masterEnabled) {
            applyState(EffectState(), false)
            return
        }
        if (enabled) {
            stopSessionMonitor()
            releaseAllSessions()
        } else {
            globalEffect?.let { it.enabled = false; it.release() }
            globalEffect = null
        }
        scope.launch {
            applyState(ViperDispatcher.loadFullStateFromPrefs(repository), true)
        }
    }

    companion object {
        const val ACTION_START = "com.k100pm.tuner.v4a2.service.START"
        const val ACTION_STOP = "com.k100pm.tuner.v4a2.service.STOP"
        const val ACTION_TOGGLE_MASTER = "com.k100pm.tuner.v4a2.service.TOGGLE_MASTER"
        const val EXTRA_MASTER_ENABLED = "com.k100pm.tuner.v4a2.service.EXTRA_MASTER_ENABLED"

        /** 开机（模块拉起）一次性恢复参数：startService + ACTION_START，写完即关。 */
        fun startService(context: Context) {
            val i = Intent(context, ViperService::class.java)
            i.action = ACTION_START
            context.startService(i)
        }

        fun toggleMaster(
            context: Context,
            enabled: Boolean,
        ) {
            val i =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_TOGGLE_MASTER
                    putExtra(EXTRA_MASTER_ENABLED, enabled)
                }
            context.startService(i)
        }
    }
}