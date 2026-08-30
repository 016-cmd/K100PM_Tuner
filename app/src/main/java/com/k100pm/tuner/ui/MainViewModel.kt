package com.k100pm.tuner.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.k100pm.tuner.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel : ViewModel() {

    data class ModuleStatus(
        val isInstalled: Boolean = false,
        val version: String = "检测中...",
        val edition: String = "-",
        val isChecking: Boolean = true
    )

    private val _moduleStatus = MutableStateFlow(ModuleStatus())
    val moduleStatus: StateFlow<ModuleStatus> = _moduleStatus.asStateFlow()

    private val _controlValues = MutableStateFlow<Map<Int, String>>(emptyMap())
    val controlValues: StateFlow<Map<Int, String>> = _controlValues.asStateFlow()

    private val _hasRoot = MutableStateFlow<Boolean?>(null)
    val hasRoot: StateFlow<Boolean?> = _hasRoot.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val prefs by lazy { AppContextHolder.ctx.getSharedPreferences("k100pm_tuner", Context.MODE_PRIVATE) }

    init {
        if (prefs.getBoolean("root_granted", false)) {
            // 之前已授权 → 静默 su 验证;Magisk 永久授权不弹窗
            autoDetect()
        } else {
            // 首次使用 → 不调 su,等用户手动激活
            _moduleStatus.update { it.copy(isChecking = false, version = "点击下方按钮激活") }
        }
    }

    private fun autoDetect() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootOk = WsaShell.hasRoot()
            _hasRoot.value = rootOk
            if (rootOk) {
                prefs.edit().putBoolean("root_granted", true).apply()
                doDetectAndLoad()
            } else {
                // 权限被收回
                prefs.edit().putBoolean("root_granted", false).apply()
                _moduleStatus.update { it.copy(isChecking = false, version = "点击下方按钮激活") }
            }
        }
    }

    fun requestRootAndDetect() {
        viewModelScope.launch(Dispatchers.IO) {
            _moduleStatus.update { it.copy(isChecking = true) }
            val rootOk = WsaShell.hasRoot()
            _hasRoot.value = rootOk
            if (rootOk) {
                prefs.edit().putBoolean("root_granted", true).apply()
                doDetectAndLoad()
            } else {
                _moduleStatus.update { it.copy(isChecking = false, version = "请先在 Magisk 中授权 Root") }
            }
        }
    }

    private fun doDetectAndLoad() {
        ModuleDetector.detect()
        _moduleStatus.update {
            it.copy(isInstalled = ModuleDetector.isInstalled, version = ModuleDetector.installedVersion, edition = ModuleDetector.edition, isChecking = false)
        }
        // 仅立即刷新一次显示初值；持续轮询由 onMainScreenVisible(true) 驱动（主页可见才跑）
        if (canEdit) refreshAllControls()
    }

    // ── 自动轮询（仅主页 tab 可见时才运行,切走/退出即停止,避免后台空轮询耗电/高频 su） ──
    private var autoRefreshJob: Job? = null

    fun onMainScreenVisible(visible: Boolean) {
        if (visible) startAutoRefresh() else stopAutoRefresh()
    }

    private fun startAutoRefresh() {
        if (!canEdit) return
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                val newValues = mutableMapOf<Int, String>()
                ControlRegistry.all.forEach { ctrl ->
                    newValues[ctrl.tinymixId] = WsaShell.getTinymix(ctrl.tinymixId)
                }
                _controlValues.update { newValues }
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun refreshAllControls() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            val newValues = mutableMapOf<Int, String>()
            ControlRegistry.all.forEach { ctrl ->
                newValues[ctrl.tinymixId] = WsaShell.getTinymix(ctrl.tinymixId)
            }
            _controlValues.update { newValues }
            _isRefreshing.value = false
        }
    }

    fun setControl(control: WsaControl, value: String) {
        viewModelScope.launch {
            val success = WsaShell.setTinymix(control.tinymixId, value)
            if (success) {
                _controlValues.update { it + (control.tinymixId to value) }
            }
        }
    }

    fun getValue(id: Int): String =
        _controlValues.value[id] ?: "—"

    val canEdit: Boolean
        get() = _moduleStatus.value.isInstalled && _hasRoot.value == true
}