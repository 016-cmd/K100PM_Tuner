package com.k100pm.tuner.service

/**
 * K100PM 音质模块检测器.
 *
 * 通过 Root 检测 Magisk 模块安装状态.
 */
object ModuleDetector {

    @Volatile var isInstalled = false; private set
    @Volatile var installedVersion = "未安装"; private set
    @Volatile var edition = "未知"; private set

    private const val MODULE_NAME = "k100pm_audio_plus"

    /** 动态检测模块根路径 */
    private var _moduleBase: String? = null
    private fun moduleBase(): String {
        if (_moduleBase == null) {
            _moduleBase = findModuleBase()
        }
        return _moduleBase ?: "/data/adb/modules/$MODULE_NAME"
    }

    private fun findModuleBase(): String? {
        for (prefix in listOf("/data/adb/modules", "/data/adb/ksu/modules", "/data/adb/ap/modules")) {
            val path = "$prefix/$MODULE_NAME"
            val result = WsaShell.execSyncCmd("[ -d $path ] && echo yes || echo no")
            if (result.contains("yes")) return path
        }
        return null
    }

    fun detect() {
        val modProp = WsaShell.execSyncCmd("cat ${moduleBase()}/module.prop 2>/dev/null")
        if (modProp.isNotBlank()) {
            isInstalled = true
            installedVersion = modProp.lines().firstOrNull { it.startsWith("version=") }?.substringAfter("=")?.trim() ?: "未知版本"
            edition = when {
                // K100PM 不分公开/私人，只分：A版（无v4a）/ 普通版（含v4a）
                modProp.contains("Viper4Android", true) ||
                    modProp.contains("v4a", true) -> "普通版(含v4a)"
                modProp.contains("v1.2A", true) -> "A版(无v4a)"
                else -> "未知"
            }
        } else {
            isInstalled = false; installedVersion = "未安装"; edition = "未知"
        }
    }
}