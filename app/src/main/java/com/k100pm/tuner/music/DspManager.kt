package com.k100pm.tuner.music

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DspManager — 全局调音管理器
 * - 管理 AudioChainService 生命周期
 * - 提供 PreEQ / PostEQ 频段读写
 * - 导出预设（JSON）
 * - 持久化预设到 SharedPreferences
 */
object DspManager {
    const val TAG = "DspManager"

    // ── 状态 ──
    private val _chainReady = MutableStateFlow(false)
    val chainReady: StateFlow<Boolean> = _chainReady.asStateFlow()

    private val _chainMessage = MutableStateFlow("未初始化")
    val chainMessage: StateFlow<String> = _chainMessage.asStateFlow()

    // PreEQ 10段增益 (dB)
    private val _preBands = MutableStateFlow(FloatArray(10) { 0f })
    val preBands: StateFlow<FloatArray> = _preBands.asStateFlow()

    // PostEQ 10段增益 (dB)
    private val _postBands = MutableStateFlow(FloatArray(10) { 0f })
    val postBands: StateFlow<FloatArray> = _postBands.asStateFlow()

    // 总开关
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // 输入增益
    private val _inputGain = MutableStateFlow(0f)
    val inputGain: StateFlow<Float> = _inputGain.asStateFlow()

    // ── 预设持久化 ──
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("k100pm_dsp", Context.MODE_PRIVATE)
        loadPreset()
        startService(context)
    }

    private fun startService(context: Context) {
        // ⚠️ 已保险禁用：AudioChainService 链路整体废弃（见 AudioChainService.kt 头部说明）。
        // 目前音效由「模块 + DolbyManager」链路承担，本服务不再启用。
        // 即使此方法被调用，也不再 startForegroundService，防止常驻耗电/反射连音频链。
        // 如需复用，取消下面两行注释即可恢复：
        // val intent = Intent(context, AudioChainService::class.java)
        // try {
        //     context.startForegroundService(intent)
        //     Log.i(TAG, "AudioChainService 启动指令已发送")
        // } catch (e: Exception) {
        //     Log.e(TAG, "启动 AudioChainService 失败", e)
        // }
        Log.i(TAG, "AudioChainService 已废弃禁用，跳过启动")
    }

    fun refreshStatus() {
        val svc = AudioChainService.instance
        if (svc != null) {
            _chainReady.value = AudioChainService.chainReady
            _chainMessage.value = AudioChainService.chainMessage
        } else {
            _chainReady.value = false
            _chainMessage.value = "服务未运行"
        }
    }

    // ── 频段控制 ──

    fun setPreBand(band: Int, gainDb: Float) {
        if (band < 0 || band >= 10) return
        val arr = _preBands.value.clone()
        arr[band] = gainDb
        _preBands.value = arr
        AudioChainService.instance?.setPreEqBand(band, gainDb)
        savePreset()
    }

    fun setPostBand(band: Int, gainDb: Float) {
        if (band < 0 || band >= 10) return
        val arr = _postBands.value.clone()
        arr[band] = gainDb
        _postBands.value = arr
        AudioChainService.instance?.setPostEqBand(band, gainDb)
        savePreset()
    }

    fun setInputGain(gainDb: Float) {
        _inputGain.value = gainDb
        AudioChainService.instance?.setInputGain(gainDb)
        savePreset()
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        AudioChainService.instance?.enableDp(enabled)
        savePreset()
    }

    fun resetAll() {
        for (i in 0 until 10) {
            setPreBand(i, 0f)
            setPostBand(i, 0f)
        }
        setInputGain(0f)
        setEnabled(false)
    }

    // ── 预设持久化 ──

    private fun savePreset() {
        val p = prefs ?: return
        p.edit().apply {
            for (i in 0 until 10) {
                putFloat("pre_$i", _preBands.value[i])
                putFloat("post_$i", _postBands.value[i])
            }
            putFloat("inputGain", _inputGain.value)
            putBoolean("enabled", _enabled.value)
            apply()
        }
    }

    private fun loadPreset() {
        val p = prefs ?: return
        val preArr = FloatArray(10)
        val postArr = FloatArray(10)
        for (i in 0 until 10) {
            preArr[i] = p.getFloat("pre_$i", 0f)
            postArr[i] = p.getFloat("post_$i", 0f)
        }
        _preBands.value = preArr
        _postBands.value = postArr
        _inputGain.value = p.getFloat("inputGain", 0f)
        _enabled.value = p.getBoolean("enabled", false)
    }

    // ── 预设导出/导入 ──

    fun exportJson(): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"enabled\": ${_enabled.value},\n")
        sb.append("  \"inputGain\": ${_inputGain.value},\n")
        sb.append("  \"preEq\": [")
        sb.append(_preBands.value.joinToString(", ") { "%.1f".format(it) })
        sb.append("],\n")
        sb.append("  \"postEq\": [")
        sb.append(_postBands.value.joinToString(", ") { "%.1f".format(it) })
        sb.append("]\n}")
        return sb.toString()
    }

    fun importJson(json: String): Boolean {
        return try {
            val trimmed = json.trim()
            // 简单正则解析 JSON
            val enabledRegex = """"enabled"\s*:\s*(true|false)""".toRegex()
            val inputGainRegex = """"inputGain"\s*:\s*([-\d.]+)""".toRegex()
            val preEqRegex = """"preEq"\s*:\s*\[([^\]]*)\]""".toRegex()
            val postEqRegex = """"postEq"\s*:\s*\[([^\]]*)\]""".toRegex()

            enabledRegex.find(trimmed)?.groupValues?.get(1)?.let {
                _enabled.value = it == "true"
                AudioChainService.instance?.enableDp(it == "true")
            }
            inputGainRegex.find(trimmed)?.groupValues?.get(1)?.let {
                val g = it.toFloatOrNull()
                if (g != null) setInputGain(g)
            }
            preEqRegex.find(trimmed)?.groupValues?.get(1)?.let { arr ->
                arr.split(",").map { it.trim().toFloatOrNull() ?: 0f }.take(10).forEachIndexed { i, v ->
                    setPreBand(i, v)
                }
            }
            postEqRegex.find(trimmed)?.groupValues?.get(1)?.let { arr ->
                arr.split(",").map { it.trim().toFloatOrNull() ?: 0f }.take(10).forEachIndexed { i, v ->
                    setPostBand(i, v)
                }
            }
            savePreset()
            true
        } catch (e: Exception) {
            Log.e(TAG, "importJson failed", e)
            false
        }
    }

    // ── 预设名称列表 ──

    data class Preset(val name: String, val preGains: FloatArray, val postGains: FloatArray, val inputGain: Float)

    fun getBuiltinPresets(): List<Preset> = listOf(
        Preset("扁平", FloatArray(10) { 0f }, FloatArray(10) { 0f }, 0f),
        Preset("流行", floatArrayOf(6f, 4f, 0f, -2f, 0f, 2f, 4f, 6f, 4f, 0f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("摇滚", floatArrayOf(8f, 6f, 2f, -4f, -2f, 0f, 2f, 4f, 6f, 8f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("古典", floatArrayOf(0f, 0f, 0f, 0f, 0f, -2f, -4f, -6f, -4f, -2f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("爵士", floatArrayOf(4f, 2f, 0f, 2f, 4f, 2f, 0f, 0f, -2f, 0f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("电子", floatArrayOf(6f, 4f, 0f, -6f, 0f, 4f, 6f, 8f, 6f, 4f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("人声增强", floatArrayOf(-4f, -2f, 0f, 4f, 6f, 4f, 0f, -2f, -2f, 0f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
        Preset("低音增强", floatArrayOf(10f, 8f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
               floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), 0f),
    )

    fun applyPreset(preset: Preset) {
        for (i in 0 until 10) setPreBand(i, preset.preGains[i])
        for (i in 0 until 10) setPostBand(i, preset.postGains[i])
        setInputGain(preset.inputGain)
        setEnabled(true)
    }
}