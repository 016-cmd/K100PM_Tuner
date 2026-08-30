package com.k100pm.tuner.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * K100PM 杜比调音台管理器
 *
 * 功能:
 * - 读取 /odm/etc/dolby/dax-default.xml 解析当前参数
 * - 修改 Default profile speaker 层的参数
 * - 修改 Tuning 层三场景（large/medium/small）的 BE/VB 精细参数
 * - 从模块 factory 目录的原始文件恢复（重置）
 * - 应用修改:写入模块目录文件 → 重启音频服务即时生效
 *
 * 写入流程（即时生效,无需重启手机）:
 *   ① cat $DAX_SYS 读当前系统值作为模板（root,单行命令）
 *   ② Kotlin 正则替换修改参数（APP 内存中）
 *   ③ 写入 APP 内部目录文件（context.filesDir,不需要 root,无长度限制）
 *   ④ su -c cp 覆盖到模块 Link 目录（Magisk 开机 bind mount 的源文件）
 *   ⑤ su -c cp 覆盖到模块 vendor 目录（与 Link 保持 MD5 一致）
 *   ⑥ 重启 audioserver 使杜比引擎重新加载（mount --bind 已同步,即时生效）
 *
 * ⚠ 即时生效原理:
 * 模块 post-fs-data.sh 通过 mount --bind 将 Link/ 和 vendor/ 挂载到系统路径,
 * 写模块源文件即同步到系统路径,重启 audioserver 使杜比引擎重新读取即可生效.
 * 不需要重启手机.但请不要频繁应用参数,每次应用都会重启音频服务.
 *
 * 注意:K100PM 已统一 vendor 与 Link/odm 目录（学 K90PM: 两目录保持一致 MD5）,APP 同时写 vendor.
 */
object DolbyTunerManager {

    private const val TAG = "K100PM_DolbyTuner"
    private const val MODULE_NAME = "k100pm_audio_plus"

    /** 动态检测模块根路径（兼容 Magisk/KSU/APatch） */
    private var _moduleBase: String? = null
    private fun moduleBase(): String {
        if (_moduleBase == null) {
            _moduleBase = findModuleBase()
        }
        return _moduleBase ?: "/data/adb/modules/$MODULE_NAME"
    }

    /** 在多个可能路径中查找模块目录 */
    private fun findModuleBase(): String? {
        for (prefix in listOf("/data/adb/modules", "/data/adb/ksu/modules", "/data/adb/ap/modules")) {
            val path = "$prefix/$MODULE_NAME"
            val result = WsaShell.execSyncCmd("[ -d $path ] && echo yes || echo no")
            if (result.contains("yes")) return path
        }
        return null
    }

    /** 系统杜比文件路径（只读,仅用于读取模板和解析） */
    private const val DAX_SYS = "/odm/etc/dolby/dax-default.xml"

    /** 模块 Link 目录下的杜比主文件（Magisk 开机 bind mount 到 /odm/etc/dolby/ 的源文件） */
    private val MODULE_DAX get() = "${moduleBase()}/Link/odm/etc/dolby/dax-default.xml"

    // K100PM 已统一 vendor 与 Link/odm 目录（学 K90PM: 两目录保持一致,MD5 一致）,APP 同时写 vendor
    /** 模块 vendor 目录下的杜比文件（已与 Link/odm 保持一致,APP 同步写入） */
    private val MODULE_VENDOR_DAX get() = "${moduleBase()}/vendor/etc/dolby/dax-default.xml"

    /** 模块 factory 目录下的原始杜比文件（不参与挂载,仅用于 APP 重置恢复） */
    private val MODULE_FACTORY_DAX get() = "${moduleBase()}/factory/etc/dolby/dax-default.xml"

    /** 模块版本快照文件 */
    private val VERSION_SNAPSHOT = "/data/local/tmp/k100pm_tuner_module_version.txt"

    /** DAX3 快照文件 —— 桥接 APP 与 service.sh,重启后恢复 APP 设定的 DAX3 属性 */
    private const val DAX3_SNAPSHOT = "/data/local/tmp/k100pm_dax3_snapshot.json"

    // ═══════════════════════════════════════════
    //  DAX3 系统属性默认值（与 service.sh 写死值一致）
    //  ═══════════════════════════════════════════
    val DAX3_DEFAULTS = mapOf(
        "persist.vendor.dolby.global.enable" to "1",
        "persist.vendor.dolby.dialog.enhancer.enable" to "1",
        "persist.vendor.dolby.dialog.enhancer.amount" to "7",
        "persist.vendor.dolby.volume.leveler.enable" to "0",
        "persist.vendor.dolby.volume.leveler.amount" to "0",
        "persist.vendor.dolby.virtualizer.enable" to "1",
        "persist.vendor.dolby.virtualizer.amount" to "6",
        "persist.vendor.dolby.bass.enable" to "1",
        "persist.vendor.dolby.bass.boost" to "0",
        "persist.vendor.dolby.spectral.enable" to "1",
        "persist.vendor.dolby.spectral.boost" to "7",
        "persist.vendor.dolby.ieq.enable" to "0",
        "vendor.dolby.dap.pcequal" to "0",
        "vendor.dolby.dap.pctype" to "0"
    )

    // ═══════════════════════════════════════════
    //  数据模型
    // ═══════════════════════════════════════════

    data class DolbyParams(
        // ── 快速开关区 ──
        val dialogEnhancerEnable: Boolean = true,
        val dialogEnhancerAmount: Int = 5,
        val dialogEnhancerDucking: Int = 0,
        val bassEnhancerEnable: Boolean = false,
        val virtualBassProcessEnable: Boolean = false,
        val surroundDecoderEnable: Boolean = true,
        val surroundBoost: Int = 105,
        val volumeLevelerEnable: Boolean = true,     // → volume-leveler-amount 0/1
        val virtualizerEnable: Boolean = false,
        val virtualizerStartBand: Int = 0,
        val miSurroundCompressorSteeringEnable: Boolean = false,  // → mi-surround-compressor-steering-enable (环绕压缩器舵向)
        val calibrationBoost: Int = 0,
        // IEQ 增强开关
        val ieqEnhanceEnable: Boolean = false,
        val volmaxBoost: Int = 50,
        val peakValue: Int = 1024,
        val hearingProtectionEnable: Boolean = false,

        // ── 快速开关区（低频提取） ──
        val bassExtractionEnable: Boolean = false,

        // ── 高级区（BE精细,三场景同步） ──
        val bassEnhancerBoost: Int = 200,
        val bassEnhancerCutoffFrequency: Int = 150,

        // ── 高级区（VB精细,三场景同步） ──
        val virtualBassMode: Int = 3,
        val virtualBassOverallGain: Int = 35,
        val virtualBassMixLow: Int = 30,
        val virtualBassMixHigh: Int = 150,

        // ── 高级区（低频提取精细,三场景同步） ──
        val bassExtractionCutoffFrequency: Int = 65,
        
        // ── 动态范围优化 ──
        val regulatorStressOptimize: Boolean = false
    )

    // ═══════════════════════════════════════════
    //  频段调节（band_optimizer）数据模型
    //  ═══════════════════════════════════════════
    //  机制:以 Medium 场景为基准,用户拖动滑杆产生"偏移量 Δ",
    //  写回时三场景（large/medium/small）各自用"基值 + Δ"同比修改.
    //  同一个频段三场景共享一个 Δ,保证三场景频响形状/相对关系不变.

    /** 三场景 speaker_landscape_X 的 tuning 名 */
    private val SCENE_NAMES = listOf(
        "speaker_landscape_large",
        "speaker_landscape_medium",
        "speaker_landscape_small"
    )

    /** 主声道全部 20 个频段（顺序与 dax 中一致） */
    val ALL_BANDS = listOf(
        47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
        2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
    )

    /** 低音单元（gain_left_surround）只暴露的低频段 */
    val BASS_BANDS = listOf(47, 141, 234, 328)

    /** 可调声道 */
    enum class BandChannel(val xmlKey: String) {
        LEFT("gain_left"),
        RIGHT("gain_right"),
        SURROUND("gain_left_surround")
    }

    /** 每个频段的偏移量 Δ（三场景共享,以 Medium 基值为 0 基准） */
    data class BandOffsets(
        val left: MutableMap<Int, Int> = ALL_BANDS.associateWith { 0 }.toMutableMap(),
        val right: MutableMap<Int, Int> = ALL_BANDS.associateWith { 0 }.toMutableMap(),
        val surround: MutableMap<Int, Int> = BASS_BANDS.associateWith { 0 }.toMutableMap()
    )

    /** 三场景各自 band_optimizer 的绝对基值,用于写回时 +Δ */
    data class SceneBaselines(
        val large: MutableMap<Int, IntArray> = mutableMapOf(),  // freq -> [L,R,S]
        val medium: MutableMap<Int, IntArray> = mutableMapOf(),
        val small: MutableMap<Int, IntArray> = mutableMapOf()
    )

    // ═══════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying.asStateFlow()

    private val _params = MutableStateFlow(DolbyParams())
    val params: StateFlow<DolbyParams> = _params.asStateFlow()

    /** 频段偏移量 Δ（三场景共享） */
    private val _bandOffsets = MutableStateFlow(BandOffsets())
    val bandOffsets: StateFlow<BandOffsets> = _bandOffsets.asStateFlow()

    /** 三场景 band_optimizer 基值（解析时填充） */
    private val _bandBaselines = MutableStateFlow(SceneBaselines())
    val bandBaselines: StateFlow<SceneBaselines> = _bandBaselines.asStateFlow()

    /** 频段是否已成功解析（无则不显示频段调节,避免误写） */
    private val _hasBandsParsed = MutableStateFlow(false)
    val hasBandsParsed: StateFlow<Boolean> = _hasBandsParsed.asStateFlow()

    private val _statusMsg = MutableStateFlow("")
    val statusMsg: StateFlow<String> = _statusMsg.asStateFlow()

    /** 操作结果弹窗消息（成功/失败,UI层读取后弹AlertDialog） */
    private val _resultMsg = MutableStateFlow("")
    val resultMsg: StateFlow<String> = _resultMsg.asStateFlow()

    /** 模块 factory 目录是否存在（旧版模块不支持一键重置） */
    private val _hasFactoryDax = MutableStateFlow(false)
    val hasFactoryDax: StateFlow<Boolean> = _hasFactoryDax.asStateFlow()

    /** DAX3 系统属性（key → value,均字符串） */
    private val _dax3 = MutableStateFlow(DAX3_DEFAULTS.toMap())
    val dax3: StateFlow<Map<String, String>> = _dax3.asStateFlow()

    /** DAX3 中用户实际修改过的 key（仅这些写入快照和 setprop,不影响未改动的属性） */
    private val _dax3Dirty = mutableSetOf<String>()

    private var hasParsedOnce = false
    private var factoryWarnShownThisSession = false

    // ═══════════════════════════════════════════
    //  初始化 & 读取
    // ═══════════════════════════════════════════

    /** 从系统 /odm/etc/dolby/dax-default.xml 读取当前参数 */
    suspend fun loadParams(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            // 先检测模块是否安装
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _statusMsg.value = "❌ 检测到未安装 REDMI K100 Pro Max 音质优化 by 016. 模块,请先安装模块"
                _isLoading.value = false
                return@withContext false
            }

            // 检测模块版本是否有变化
            checkModuleVersionChanged(context)

            // 检测模块 factory 原始文件是否存在（旧版模块不支持一键重置）
            val factoryCheck = WsaShell.execSyncCmd("[ -f $MODULE_FACTORY_DAX ] && echo yes || echo no")
            _hasFactoryDax.value = factoryCheck.contains("yes")

            val xml = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
            if (xml.isBlank()) {
                _statusMsg.value = "无法读取杜比文件（模块未安装或无权限）"
                _isLoading.value = false
                return@withContext false
            }
            parseAndSet(xml)
            // 同步加载 DAX3 系统属性
            loadDax3Props()
            hasParsedOnce = true
            _statusMsg.value = ""
            _isLoading.value = false
            true
        } catch (e: Exception) {
            _statusMsg.value = "读取失败: ${e.message}"
            _isLoading.value = false
            false
        }
    }

    /** 检测模块 factory 原始文件是否存在（旧版模块不支持一键重置） */
    fun checkFactoryDaxExists(): Boolean {
        val result = WsaShell.execSyncCmd("[ -f $MODULE_FACTORY_DAX ] && echo yes || echo no")
        val exists = result.contains("yes")
        _hasFactoryDax.value = exists
        return exists
    }

    /** 会话期内是否已弹过 factory 缺失警告 */
    fun isFactoryWarnShownThisSession(): Boolean = factoryWarnShownThisSession
    fun markFactoryWarnShown() { factoryWarnShownThisSession = true }

    /** 从模块 factory 目录加载原始参数（用于重置时获取原始值预览） */
    suspend fun loadModuleDefault(): DolbyParams? = withContext(Dispatchers.IO) {
        try {
            val xml = WsaShell.execSyncCmd("cat $MODULE_FACTORY_DAX 2>/dev/null")
            if (xml.isBlank()) return@withContext null
            parseParams(xml)
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════════
    //  解析核心
    // ═══════════════════════════════════════════

    private fun parseAndSet(xml: String) {
        _params.value = parseParams(xml)
        // 同步解析 band_optimizer 三场景基值（频段调节用）
        parseBandOptimizers(xml)
    }

    private fun parseParams(xml: String): DolbyParams {
        return DolbyParams(
            // dialog-enhancer
            dialogEnhancerEnable = extractBool(xml, "dialog-enhancer-enable"),
            dialogEnhancerAmount = extractInt(xml, "dialog-enhancer-amount", 5),
            dialogEnhancerDucking = extractInt(xml, "dialog-enhancer-ducking", 0),

            // bass-enhancer
            bassEnhancerEnable = extractBool(xml, "bass-enhancer-enable"),

            // virtual-bass
            virtualBassProcessEnable = extractBool(xml, "virtual-bass-process-enable"),

            // surround
            surroundDecoderEnable = extractBool(xml, "surround-decoder-enable"),
            surroundBoost = extractInt(xml, "surround-boost", 105),

            // volume-leveler（用amount 0/1 当开关）
            volumeLevelerEnable = extractInt(xml, "volume-leveler-amount", 0) == 1,

            // virtualizer
            virtualizerEnable = extractBool(xml, "virtualizer-enable"),
            virtualizerStartBand = extractInt(xml, "virtualizer-start-band", 0),

            // 环绕压缩器舵向
            miSurroundCompressorSteeringEnable = extractBool(xml, "mi-surround-compressor-steering-enable"),

            // calibration / volmax / peak
            calibrationBoost = extractInt(xml, "calibration-boost", 0),
            ieqEnhanceEnable = extractBool(xml, "ieq-enable"),
            volmaxBoost = extractInt(xml, "volmax-boost", 50),
            peakValue = extractInt(xml, "peak-value", 1024),

            // hearing-protection
            hearingProtectionEnable = extractBool(xml, "hearing-protection-enable"),

            // ── 快速开关（低频提取） ──
            bassExtractionEnable = extractBool(xml, "bass-extraction-enable"),

            // ── 高级区（从tuning层找三场景的BE/VB值,取第一个找到的） ──
            bassEnhancerBoost = extractTuningInt(xml, "bass-enhancer-boost", 200),
            bassEnhancerCutoffFrequency = extractTuningInt(xml, "bass-enhancer-cutoff-frequency", 150),
            virtualBassMode = extractTuningInt(xml, "virtual-bass-mode", 3),
            virtualBassOverallGain = extractTuningInt(xml, "virtual-bass-overall-gain", 35),
            virtualBassMixLow = extractTuningIntRangeLow(xml, "virtual-bass-mix-freqs", 30),
            virtualBassMixHigh = extractTuningIntRangeHigh(xml, "virtual-bass-mix-freqs", 150),

            // ── 高级区（低频提取精细） ──
            bassExtractionCutoffFrequency = extractTuningInt(xml, "bass-extraction-cutoff-frequency", 65),
            
            // ── 动态范围优化 ──
            regulatorStressOptimize = extractTuningString(xml, "regulator-stress-amount", "120,116,112,64") == "240,240,240,96"
        )
    }

    /** 更新参数（不写文件,只更新内存状态） */
    fun updateParams(new: DolbyParams) {
        _params.value = new
    }

    /** 设置状态提示信息（UI可用） */
    fun setStatusMsg(msg: String) {
        _statusMsg.value = msg
    }

    /** 设置结果弹窗消息（UI层弹AlertDialog） */
    fun setResultMsg(msg: String) {
        _resultMsg.value = msg
    }

    /** 清除结果弹窗消息 */
    fun clearResultMsg() {
        _resultMsg.value = ""
    }

    // ═══════════════════════════════════════════
    //  DAX3 系统属性管理
    //  ═══════════════════════════════════════════

    /**
     * 从系统 getprop 读取所有 DAX3 属性,填充 _dax3.
     * 在 loadParams 时自动调用.
     */
    private fun loadDax3Props() {
        try {
            val map = mutableMapOf<String, String>()
            for (key in DAX3_DEFAULTS.keys) {
                val v = WsaShell.execSyncCmd("getprop $key 2>/dev/null").trim()
                map[key] = if (v.isBlank()) DAX3_DEFAULTS[key]!! else v
            }
            _dax3.value = map
        } catch (_: Exception) {
            _dax3.value = DAX3_DEFAULTS.toMap()
        }
    }

    /** 更新内存中的单个 DAX3 属性（不立即写入系统） */
    fun updateDax3(key: String, value: String) {
        _dax3.value = _dax3.value.toMutableMap().also { it[key] = value }
        _dax3Dirty.add(key)
    }

    /**
     * 将用户修改过的 DAX3 属性写入系统 + 持久化快照.
     * 仅在 applyChanges / resetToModuleDefault 时调用.
     * 只写入 _dax3Dirty 中标记过的属性,不影响未改动的 DAX3 属性.
     */
    private fun applyDax3ToSystem() {
        try {
            if (_dax3Dirty.isEmpty()) return  // 没改过任何 DAX3 参数,跳过
            val sb = StringBuilder("{\n")
            for (key in _dax3Dirty) {
                val value = _dax3.value[key] ?: continue
                WsaShell.execSyncCmd("setprop $key '$value' 2>/dev/null")
                WsaShell.execSyncCmd("resetprop $key '$value' 2>/dev/null")
                sb.append("  \"$key\": \"$value\",\n")
            }
            val json = sb.toString().trimEnd('\n').trimEnd(',') + "\n}\n"
            WsaShell.execSyncCmd("cat > $DAX3_SNAPSHOT << 'DAX3EOF'\n$json\nDAX3EOF")
        } catch (_: Exception) {
            // 静默
        }
    }

    // ═══════════════════════════════════════════
    //  应用修改
    // ═══════════════════════════════════════════

    /**
     * 将当前 params 写入模块目录的杜比文件,重启音频服务即时生效.
     *
     * 写入流程（即时生效,无需重启手机）:
     *   ① cat $DAX_SYS 读当前系统值作为模板（root,单行命令）
     *   ② Kotlin 正则替换修改参数（APP 内存中）
     *   ③ 写入 APP 内部目录文件（context.filesDir,不需要 root,无长度限制）
     *   ④ su -c cp 覆盖到模块 Link 目录（Magisk 开机 bind mount 的源文件）
     *   ⑤ su -c cp 覆盖到模块 vendor 目录（与 Link 保持 MD5 一致）
     *   ⑥ 重启 audioserver 使杜比引擎重新加载（mount --bind 已同步,即时生效）
     *   ⑦ 提示"参数已保存并生效!"
     *
     * 注意:不写入模块 odm 目录（该目录不参与系统挂载,与模块机制统一）.
     * 请不要频繁应用参数,每次应用都会重启音频服务.
     */
    suspend fun applyChanges(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isApplying.value = true
        _statusMsg.value = ""
        _resultMsg.value = ""
        try {
            // 0. 先验证 Root 权限
            val rootCheck = WsaShell.execSyncCmd("echo OK")
            if (!rootCheck.contains("OK")) {
                _resultMsg.value = "❌ 无 Root 权限,请在 Magisk 中授予本应用 Root 权限"
                _isApplying.value = false
                return@withContext false
            }

            // 0.5 检测模块是否安装
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _resultMsg.value = "❌ 检测到未安装 REDMI K100 Pro Max 音质优化 by 016. 模块,请先安装模块"
                _isApplying.value = false
                return@withContext false
            }

            // 1. 读取当前系统文件作为模板
            val original = WsaShell.execSyncCmd("cat $DAX_SYS 2>/dev/null")
            if (original.isBlank()) {
                _resultMsg.value = "❌ 无法读取杜比文件,请确认模块已正确安装"
                _isApplying.value = false
                return@withContext false
            }

            // 2. 用 Kotlin 字符串替换修改参数（避免 toybox sed 引号转义问题）
            val p = _params.value
            var modified = original

            // ── speaker 层参数 ──
            modified = replaceInSpeaker(modified, "dialog-enhancer-enable", boolToXml(p.dialogEnhancerEnable))
            modified = replaceInSpeaker(modified, "dialog-enhancer-amount", p.dialogEnhancerAmount.toString())
            modified = replaceInSpeaker(modified, "dialog-enhancer-ducking", p.dialogEnhancerDucking.toString())
            modified = replaceInSpeaker(modified, "bass-enhancer-enable", boolToXml(p.bassEnhancerEnable))
            modified = replaceInSpeaker(modified, "virtual-bass-process-enable", boolToXml(p.virtualBassProcessEnable))
            modified = replaceInSpeaker(modified, "surround-decoder-enable", boolToXml(p.surroundDecoderEnable))
            modified = replaceInSpeaker(modified, "surround-boost", p.surroundBoost.toString())
            modified = replaceInSpeaker(modified, "volume-leveler-amount", if (p.volumeLevelerEnable) "1" else "0")
            modified = replaceInSpeaker(modified, "virtualizer-enable", boolToXml(p.virtualizerEnable))
            modified = replaceInSpeaker(modified, "virtualizer-start-band", p.virtualizerStartBand.toString())
            modified = replaceInSpeaker(modified, "mi-surround-compressor-steering-enable", boolToXml(p.miSurroundCompressorSteeringEnable))
            modified = replaceInSpeaker(modified, "calibration-boost", p.calibrationBoost.toString())
            // ── IEQ 增强（APP 只切换外放 speaker 的开关,不碰耳机 headphone 端点）──
            modified = replaceInSpeakerEndpoints(modified, "ieq-enable", boolToXml(p.ieqEnhanceEnable))
            modified = replaceInSpeaker(modified, "volmax-boost", p.volmaxBoost.toString())
            modified = replaceInAllProfiles(modified, "peak-value", p.peakValue.toString())
            modified = replaceInSpeaker(modified, "hearing-protection-enable", boolToXml(p.hearingProtectionEnable))

            // ── Tuning 层三场景同步 ──
            modified = replaceInAllTunings(modified, "bass-enhancer-boost", p.bassEnhancerBoost.toString())
            modified = replaceInAllTunings(modified, "bass-enhancer-cutoff-frequency", p.bassEnhancerCutoffFrequency.toString())
            modified = replaceInAllTunings(modified, "virtual-bass-mode", p.virtualBassMode.toString())
            modified = replaceInAllTunings(modified, "virtual-bass-overall-gain", p.virtualBassOverallGain.toString())
            modified = replaceMixFreqs(modified, p.virtualBassMixLow, p.virtualBassMixHigh)

            // ── Tuning 层三场景同步（低频提取） ──
            modified = replaceInAllTunings(modified, "bass-extraction-enable", boolToXml(p.bassExtractionEnable))
            modified = replaceInAllTunings(modified, "bass-extraction-cutoff-frequency", p.bassExtractionCutoffFrequency.toString())
            
            // ── 动态范围优化（regulator-stress-amount） ──
            val regulatorValue = if (p.regulatorStressOptimize) "240,240,240,96" else "120,116,112,64"
            modified = replaceInAllTunings(modified, "regulator-stress-amount", regulatorValue)

            // ── Tuning 层三场景频段 band_optimizer（Medium基准 + 偏移三场景同比） ──
            modified = applyBandOffsets(modified)

            // 3. 写入 APP 内部目录（不用 heredoc,避免 shell 参数长度限制）
            val internalFile = File(context.filesDir, "k100pm_tuner_dax.xml")
            internalFile.writeText(modified, Charsets.UTF_8)
            val internalPath = internalFile.absolutePath

            // 4. su -c cp 覆盖到模块 Link 目录（Magisk 开机 bind mount 的源文件）
            val linkOk = WsaShell.execSyncCmd(
                "cp -f '$internalPath' '$MODULE_DAX' && chmod 644 '$MODULE_DAX' && echo OK || echo FAIL"
            )
            if (!linkOk.contains("OK")) {
                _resultMsg.value = "❌ 模块 Link 目录写入失败,模块可能不存在或只读"
                _isApplying.value = false
                return@withContext false
            }

            // K100PM 已统一 vendor 与 Link/odm 目录,APP 同步写 vendor（学 K90PM: 两目录保持一致 MD5）
            val vendorExists = WsaShell.execSyncCmd("[ -f $MODULE_VENDOR_DAX ] && echo yes || echo no")
            if (vendorExists.contains("yes")) {
                WsaShell.execSyncCmd("cp -f '$internalPath' '$MODULE_VENDOR_DAX' && chmod 644 '$MODULE_VENDOR_DAX'")
            }

            // 5.5. DAX3 系统属性写入 + 快照持久化
            applyDax3ToSystem()

            // 6. 重启 audioserver 使杜比引擎重新加载
            val restartResult = WsaShell.execSyncCmd("setprop ctl.restart audioserver && echo OK || echo FAIL")
            val restartOk = restartResult.contains("OK")

            if (restartOk) {
                _resultMsg.value = "✅ 参数已保存并生效!音频服务已重启,效果即时生效.请不要频繁应用参数,每次应用都需要重启音频服务,频繁重启可能导致音频服务出现问题."
            } else {
                _resultMsg.value = "✅ 参数已保存!但音频服务重启失败,请重启手机后生效."
            }
            _isApplying.value = false
            true
        } catch (e: Exception) {
            _resultMsg.value = "❌ 应用失败: ${e.message}"
            _isApplying.value = false
            false
        }
    }

    /**
     * 重置为模块默认参数,重启音频服务即时生效.
     *
     * 从模块 factory 目录读取原始杜比文件（不参与挂载,始终保留模块出厂原始值）,
     * 覆盖 Link/vendor 目录后重启 audioserver.
     */
    suspend fun resetToModuleDefault(context: Context): Boolean = withContext(Dispatchers.IO) {
        _isApplying.value = true
        _statusMsg.value = ""
        _resultMsg.value = ""
        try {
            // 0. 先验证 Root 权限
            val rootCheck = WsaShell.execSyncCmd("echo OK")
            if (!rootCheck.contains("OK")) {
                _resultMsg.value = "❌ 无 Root 权限,请在 Magisk 中授予本应用 Root 权限"
                _isApplying.value = false
                return@withContext false
            }

            // 0.5 检测模块是否安装
            ModuleDetector.detect()
            if (!ModuleDetector.isInstalled) {
                _resultMsg.value = "❌ 检测到未安装 REDMI K100 Pro Max 音质优化 by 016. 模块,请先安装模块"
                _isApplying.value = false
                return@withContext false
            }

            // 1. 从模块 factory 目录读取原始杜比文件（出厂原始值,不受 APP 修改影响）
            val factoryXml = WsaShell.execSyncCmd("cat $MODULE_FACTORY_DAX 2>/dev/null")
            if (factoryXml.isBlank()) {
                _resultMsg.value = "❌ 未找到模块出厂原始文件（factory/etc/dolby/dax-default.xml）,请确认模块版本支持"
                _isApplying.value = false
                return@withContext false
            }

            // 2. 写入 APP 内部目录
            val internalFile = File(context.filesDir, "k100pm_tuner_dax.xml")
            internalFile.writeText(factoryXml, Charsets.UTF_8)
            val internalPath = internalFile.absolutePath

            // 3. su -c cp 覆盖回模块 Link 目录
            val linkOk = WsaShell.execSyncCmd(
                "cp -f '$internalPath' '$MODULE_DAX' && chmod 644 '$MODULE_DAX' && echo OK || echo FAIL"
            )
            if (!linkOk.contains("OK")) {
                _resultMsg.value = "❌ 模块 Link 目录写入失败"
                _isApplying.value = false
                return@withContext false
            }

            // K100PM 已统一 vendor 与 Link/odm 目录,重置时同步写 vendor（学 K90PM）
            val vendorExists = WsaShell.execSyncCmd("[ -f $MODULE_VENDOR_DAX ] && echo yes || echo no")
            if (vendorExists.contains("yes")) {
                WsaShell.execSyncCmd("cp -f '$internalPath' '$MODULE_VENDOR_DAX' && chmod 644 '$MODULE_VENDOR_DAX'")
            }

            // 4.5. DAX3 重置为模块默认值 + 删除快照（让 service.sh 走 else 分支写死默认）
            _dax3.value = DAX3_DEFAULTS.toMap()
            // 重置时必须将 dirty 的 key（用户改过的）setprop 回默认值
            for (key in _dax3Dirty) {
                val defVal = DAX3_DEFAULTS[key] ?: continue
                WsaShell.execSyncCmd("setprop $key '$defVal' 2>/dev/null")
                WsaShell.execSyncCmd("resetprop $key '$defVal' 2>/dev/null")
            }
            _dax3Dirty.clear()
            WsaShell.execSyncCmd("rm -f $DAX3_SNAPSHOT 2>/dev/null")  // 删快照,重启后 service.sh 用默认

            // 5. 重启 audioserver 使杜比引擎重新加载
            val restartResult = WsaShell.execSyncCmd("setprop ctl.restart audioserver && echo OK || echo FAIL")
            val restartOk = restartResult.contains("OK")

            // 6. 重启后 Dolby HAL 初始化可能从内部状态恢复旧 persist 值,
            //    因此需要再次 setprop 覆盖,确保 DAX3 属性回到默认值
            for (key in DAX3_DEFAULTS.keys) {
                val defVal = DAX3_DEFAULTS[key]!!
                WsaShell.execSyncCmd("setprop $key '$defVal' 2>/dev/null")
                WsaShell.execSyncCmd("resetprop $key '$defVal' 2>/dev/null")
            }

            // 7. 重新读取参数刷新UI
            loadParams(context)

            if (restartOk) {
                _resultMsg.value = "✅ 已重置为模块默认参数并生效!音频服务已重启,效果即时生效."
            } else {
                _resultMsg.value = "✅ 已重置为模块默认参数!但音频服务重启失败,请重启手机后生效."
            }
            _isApplying.value = false
            true
        } catch (e: Exception) {
            _resultMsg.value = "❌ 重置失败: ${e.message}"
            _isApplying.value = false
            false
        }
    }

    // ═══════════════════════════════════════════
    //  模块版本检测
    // ═══════════════════════════════════════════

    /**
     * 检测模块版本是否有变化.
     * 读取当前模块 module.prop 中的版本信息,与上次保存的快照对比.
     * 如果有变化,清除备份文件,下次 loadParams 会重新从新模块复制备份.
     * 版本不变时不动备份文件.
     */
    private fun checkModuleVersionChanged(context: Context) {
        try {
            // 读取当前模块版本信息（version 行 + Link 文件最后修改时间）
            val modProp = WsaShell.execSyncCmd("cat ${moduleBase()}/module.prop 2>/dev/null")
            val daxModTime = WsaShell.execSyncCmd("stat -c %Y $MODULE_DAX 2>/dev/null")
            val currentVersion = (modProp + "|" + daxModTime).trim()

            // 读取上次保存的快照
            val savedVersion = WsaShell.execSyncCmd("cat $VERSION_SNAPSHOT 2>/dev/null").trim()

            if (currentVersion.isNotBlank() && currentVersion != savedVersion) {
                // 版本有变化 → 清除旧快照,下次会记录新版本
                WsaShell.execSyncCmd("rm -f $VERSION_SNAPSHOT 2>/dev/null")
                // 保存新快照
                WsaShell.execSyncCmd("echo '${currentVersion.replace("'", "'\\''")}' > $VERSION_SNAPSHOT 2>/dev/null")
            } else if (savedVersion.isBlank() && currentVersion.isNotBlank()) {
                // 首次记录快照
                WsaShell.execSyncCmd("echo '${currentVersion.replace("'", "'\\''")}' > $VERSION_SNAPSHOT 2>/dev/null")
            }
        } catch (_: Exception) {
            // 静默失败,不影响主流程
        }
    }

    // ═══════════════════════════════════════════
    //  XML 替换辅助
    // ═══════════════════════════════════════════

    /**
 * 在 profile id="0" (Default) 的 speaker endpoint 内替换参数
 */
private fun replaceInSpeaker(xml: String, paramName: String, newValue: String): String {
    // 找到 Default profile 的 speaker 区间中的单行参数
    // 匹配形如 <paramName value="xxx"/>
    val regex = Regex(
        "(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)"
    )
    // 找第一个匹配（在 speaker 段的匹配优先,但由于文件结构,第一个就是 speaker）
    return regex.replaceFirst(xml, "$1$newValue$3")
}

/**
 * 在所有 profile 的 speaker endpoint 内替换参数（严格限定于 id="speaker" 范围）.
 *
 * 用于 ieq-enable 这类既存在于 speaker 又存在于 headphone/other 的参数 —— 必须只改外放,
 * 绝不能碰到耳机(headphone)和其他(other)端点,否则 APP 的 IEQ 开关会误改耳机调音.
 */
private fun replaceInSpeakerEndpoints(xml: String, paramName: String, newValue: String): String {
    val innerRegex = Regex(
        "(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)"
    )
    val endpointRegex = Regex("<endpoint_type id=\"speaker\">(.*?)</endpoint_type>", RegexOption.DOT_MATCHES_ALL)
    // 对每个 speaker endpoint 块,替换其中的 paramName(每个块仅一处)
    return endpointRegex.replace(xml) { m ->
        val blk = m.groupValues[1]
        val newBlk = innerRegex.replaceFirst(blk, "$1$newValue$3")
        "<endpoint_type id=\"speaker\">" + newBlk + "</endpoint_type>"
    }
}

/**
 * 在所有 profile 的 speaker endpoint 内替换参数.
 * 用于 peak-value 等需要跨场景（默认/视频/语音）保持一致的全局参数.
 */
private fun replaceInAllProfiles(xml: String, paramName: String, newValue: String): String {
    val regex = Regex(
        "(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)"
    )
    // replace 全部匹配（不加 First）,覆盖文件中所有出现
    return regex.replace(xml, "$1$newValue$3")
}

    /**
     * 在所有 tuning 段替换指定参数（三场景同步）
     */
    private fun replaceInAllTunings(xml: String, paramName: String, newValue: String): String {
        val regex = Regex(
            "(<${Regex.escape(paramName)}\\s+value\\s*=\\s*\")([^\"]*)(\"\\s*/>)"
        )
        return regex.replace(xml, "$1$newValue$3")
    }

    /**
     * 替换 virtual-bass-mix-freqs 的 frequency_low / frequency_high
     */
    private fun replaceMixFreqs(xml: String, low: Int, high: Int): String {
        // 匹配 virtual-bass-mix-freqs 整行
        val regex = Regex(
            "(<virtual-bass-mix-freqs\\s+)frequency_low\\s*=\\s*\"\\d+\"\\s+frequency_high\\s*=\\s*\"\\d+\"\\s*/>"
        )
        val replacement = "<virtual-bass-mix-freqs frequency_low=\"$low\" frequency_high=\"$high\"/>"
        return regex.replace(xml, replacement)
    }

    // ═══════════════════════════════════════════
    //  频段调节（band_optimizer）核心方法
    //  ═══════════════════════════════════════════

    /**
     * 从完整 dax xml 解析三场景 band_optimizer 的绝对基值.
     * 解析顺序:SCENE_NAMES[0]=large, [1]=medium, [2]=small
     * 每个频段存 [L, R, S]（gain_left, gain_right, gain_left_surround）.
     * 解析成功才置 _hasBandsParsed=true,并重置偏移量 Δ 为 0.
     */
    private fun parseBandOptimizers(xml: String) {
        try {
            val scenes = SceneBaselines()
            var parsedCount = 0
            for ((idx, scene) in SCENE_NAMES.withIndex()) {
                // 定位该 tuning 段的 audio-optimizer-bands 区间
                val tuningStart = xml.indexOf("<tuning name=\"$scene\"")
                if (tuningStart < 0) continue
                // 段结束 = 当前 tuning 段的 </tuning>（不能用"找下一个 speaker_landscape 开头",
                // 因为 small 是三个 landscape 段的最后一个,后面紧跟 speaker_bass/speaker_loudness 等其他场景
                // 会导致 small 段错误地吞入文件尾部其他场景的频段值,覆盖掉真实基值）
                val segEnd = xml.indexOf("</tuning>", tuningStart)
                val seg = if (segEnd > tuningStart) xml.substring(tuningStart, segEnd) else xml.substring(tuningStart)

                // 每个频段各取 [L,R,S]
                val bandMap = mutableMapOf<Int, IntArray>()
                val bandRegex = Regex(
                    "frequency=\"(\\d+)\"\\s+gain_left=\"([-0-9]+)\"\\s+gain_right=\"([-0-9]+)\"\\s+gain_left_surround=\"([-0-9]+)\""
                )
                for (m in bandRegex.findAll(seg)) {
                    val f = m.groupValues[1].toIntOrNull() ?: continue
                    bandMap[f] = intArrayOf(
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                        m.groupValues[4].toInt()
                    )
                    parsedCount++
                }
                when (idx) {
                    0 -> scenes.large.putAll(bandMap)
                    1 -> scenes.medium.putAll(bandMap)
                    2 -> scenes.small.putAll(bandMap)
                }
            }
            _bandBaselines.value = scenes
        // 首次解析时偏移才归零（避免旧模块残留影响新模块）;
        // 后续 reload（切tab/应用后）保留用户已调的偏移,否则预设保存会丢失偏移量
        if (!hasParsedOnce) {
            _bandOffsets.value = BandOffsets()
        }
        _hasBandsParsed.value = parsedCount >= 20 * 3 // 至少三场景×20频段
        } catch (_: Exception) {
            _hasBandsParsed.value = false
        }
    }

    /**
     * 更新某声道某频段的偏移量 Δ（范围 -250 ~ +250）.
     * 只改内存,不写文件;应用时统一写回三场景.
     */
    fun updateBandOffset(channel: BandChannel, freq: Int, delta: Int) {
        val clamped = delta.coerceIn(-250, 250)
        val cur = _bandOffsets.value
        val next = when (channel) {
            BandChannel.LEFT -> cur.copy(left = cur.left.toMutableMap().apply { this[freq] = clamped })
            BandChannel.RIGHT -> cur.copy(right = cur.right.toMutableMap().apply { this[freq] = clamped })
            BandChannel.SURROUND -> cur.copy(surround = cur.surround.toMutableMap().apply { this[freq] = clamped })
        }
        _bandOffsets.value = next
    }

    /**
     * 重置所有频段偏移量为 0,并恢复为 factory 出厂文件的频段基准（方案B）.
     *
     * 实现:
     *   ① 读取模块 factory/etc/dolby/dax-default.xml（出厂原始文件）
     *   ② 用其中的三场景频段基值覆盖 _bandBaselines
     *   ③ 偏移量 Δ 全部归零
     *
     * 这样"重置频段偏移"与"重置为模块默认"同源（都读 factory）,
     * 恢复的是绝对出厂频段,而不是"本次加载后调过的状态".
     *
     * @return 重置是否成功（factory 文件缺失时返回 false）
     */
    suspend fun resetBandOffsets(): Boolean = withContext(Dispatchers.IO) {
        try {
            val factoryXml = WsaShell.execSyncCmd("cat $MODULE_FACTORY_DAX 2>/dev/null")
            if (factoryXml.isBlank()) {
                return@withContext false
            }
            // 重新解析出厂频段基值（覆盖当前 baselines）
            parseBandOptimizers(factoryXml)
            // 偏移量归零
            _bandOffsets.value = BandOffsets()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将当前频段偏移量 Δ 应用到完整的 dax xml,三场景同比写回.
     * 复用原有字符串替换风格,返回替换后的新 xml.
     */
    private fun applyBandOffsets(modified: String): String {
        if (!_hasBandsParsed.value) return modified
        val deltas = _bandOffsets.value
        val bases = _bandBaselines.value
        var out = modified
        for ((idx, scene) in SCENE_NAMES.withIndex()) {
            val baseMap = when (idx) {
                0 -> bases.large
                1 -> bases.medium
                else -> bases.small
            }
            if (baseMap.isEmpty()) continue
            // 对每个 band_optimizer 三声道各 +Δ
            for ((freq, arr) in baseMap) {
                // 左声道偏移（20频段全调）
                val dL = deltas.left[freq] ?: 0
                out = replaceBandGain(out, scene, freq, "gain_left", arr[0] + dL)
                // 右声道偏移（20频段全调）
                val dR = deltas.right[freq] ?: 0
                out = replaceBandGain(out, scene, freq, "gain_right", arr[1] + dR)
                // 低音单元偏移（只低频段 47~328 生效;其他频段 Δ 恒为0）
                val dS = deltas.surround[freq] ?: 0
                out = replaceBandGain(out, scene, freq, "gain_left_surround", arr[2] + dS)
            }
        }
        return out
    }

    /**
     * 在指定 tuning 场景段内,替换某 frequency 的某声道 gain 为 newVal.
     * scene: speaker_landscape_large/medium/small
     * channelKey: gain_left / gain_right / gain_left_surround
     */
    private fun replaceBandGain(xml: String, scene: String, frequency: Int, channelKey: String, newVal: Int): String {
        val sceneStart = xml.indexOf("<tuning name=\"$scene\"")
        if (sceneStart < 0) return xml
        // 段结束 = 当前 tuning 段的 </tuning>（与 parseBandOptimizers 一致,
        // 避免 small 是最后一个 landscape 段时误把文件尾部 speaker_bass/loudness 等纳入）
        val tuningEnd = xml.indexOf("</tuning>", sceneStart)
        val searchEnd = if (tuningEnd > sceneStart) tuningEnd else xml.length

        // 在该场景段内,定位 frequency 行的指定声道值.
        // 每个 band_optimizer 行含 4 个声道,均形如 channelKey="数字".
        // channelKey 作为子串也出现在 gain_left_surround/gain_right_surround 中,
        // 但只有其后紧跟 '=' 才是目标声道,因此 "channelKey=\"" 能精确锚定.
        val numReg = Regex("(<band_optimizer frequency=\"${frequency}\"[^>]*?$channelKey=\")(-?\\d+)(\")")
        val match = numReg.find(xml, sceneStart)?.takeIf { it.range.last < searchEnd } ?: return xml
        val prefix = match.groupValues[1]
        val numStart = match.range.first + prefix.length
        val numEnd = numStart + match.groupValues[2].length
        return StringBuilder(xml).replace(numStart, numEnd, newVal.toString()).toString()
    }

    // ═══════════════════════════════════════════
    //  XML 提取辅助
    // ═══════════════════════════════════════════

    private fun extractBool(xml: String, paramName: String): Boolean {
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(true|false)\"")
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
    }

    private fun extractInt(xml: String, paramName: String, default: Int): Int {
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    /** 从第一个 tuning 段提取参数值 */
    private fun extractTuningInt(xml: String, paramName: String, default: Int): Int {
        // 找第一个 tuning 段后的匹配（tuning 段的参数在文件后半部分）
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }
    
    private fun extractTuningString(xml: String, paramName: String, default: String): String {
        // 找第一个 tuning 段后的匹配（tuning 段的参数在文件后半部分）
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+value\\s*=\\s*\"([^\"]+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(1) ?: default
    }

    private fun extractTuningIntRangeLow(xml: String, paramName: String, default: Int): Int {
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+frequency_low\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun extractTuningIntRangeHigh(xml: String, paramName: String, default: Int): Int {
        val tuningStart = xml.indexOf("<tuning name=\"speaker_landscape")
        if (tuningStart < 0) return default
        val tuningXml = xml.substring(tuningStart)
        val regex = Regex("<${Regex.escape(paramName)}\\s+frequency_low\\s*=\\s*\"(-?\\d+)\"\\s+frequency_high\\s*=\\s*\"(-?\\d+)\"")
        val match = regex.find(tuningXml)
        return match?.groupValues?.get(2)?.toIntOrNull() ?: default
    }

    private fun boolToXml(b: Boolean) = if (b) "true" else "false"

    // ═══════════════════════════════════════════
    //  sed 原地替换辅助（直接修改工作文件,不经过 Kotlin 字符串）
    // ═══════════════════════════════════════════

    /**
     * 保留的 sed 替换方法（已废弃,不再被调用）.
     * 原用于直接 sed 修改工作文件,因 toybox sed 引号兼容性问题被 Kotlin 替换取代.
     * 当前使用 Kotlin 替换 + 内部文件 + su cp 写入模块 Link 目录.
     */
    private fun execSedSpeaker(param: String, value: String) {
        val safeValue = value.replace("/", "\\/").replace("&", "\\&")
        WsaShell.execSyncCmd("sed -i 's/<${param} value=\"[^\"]*\"/<${param} value=\"${safeValue}\"/g' $MODULE_DAX 2>/dev/null")
    }

    /**
     * 保留的 sed 替换方法（已废弃,不再被调用）
     */
    private fun execSedAll(param: String, value: String) {
        val safeValue = value.replace("/", "\\/").replace("&", "\\&")
        WsaShell.execSyncCmd("sed -i 's/<${param} value=\"[^\"]*\"/<${param} value=\"${safeValue}\"/g' $MODULE_DAX 2>/dev/null")
    }

    /**
     * 保留的 sed 替换方法（已废弃,不再被调用）
     */
    private fun execSedMixFreqs(low: Int, high: Int) {
        WsaShell.execSyncCmd("sed -i 's/virtual-bass-mix-freqs frequency_low=\"[^\"]*\"/virtual-bass-mix-freqs frequency_low=\"${low}\"/g' $MODULE_DAX 2>/dev/null")
        WsaShell.execSyncCmd("sed -i 's/virtual-bass-mix-freqs frequency_low=\"${low}\" frequency_high=\"[^\"]*\"/virtual-bass-mix-freqs frequency_low=\"${low}\" frequency_high=\"${high}\"/g' $MODULE_DAX 2>/dev/null")
    }

    // ═══════════════════════════════════════════
    //  预设管理（多预设,命名保存,JSON持久化）
    // ═══════════════════════════════════════════

    private const val MAX_PRESETS = 5
    private const val PREFS_NAME = "dolby_tuner_presets"
    private const val KEY_LIST = "preset_list"

    /** 预设条目 */
    data class PresetEntry(val name: String, val params: DolbyParams)

    /** 返回保存的预设名称列表 */
    fun getPresetNames(context: android.content.Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    /** 保存当前 params + DAX3 为命名预设（同名覆盖,最多 MAX_PRESETS 个） */
    fun savePreset(context: android.content.Context, name: String): String {
        val p = _params.value
        val dax3Snapshot = _dax3.value.toMap()
        val dax3DirtySnapshot = _dax3Dirty.toSet()
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        // 检查同名
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") == name) {
                arr.put(i, buildPresetJson(name, p, dax3Snapshot, dax3DirtySnapshot))
                prefs.edit().putString(KEY_LIST, arr.toString()).apply()
                return "✅ 预设「$name」已覆盖"
            }
        }

        if (arr.length() >= MAX_PRESETS) return "预设已满（最多${MAX_PRESETS}个）,请先删除旧预设"

        arr.put(buildPresetJson(name, p, dax3Snapshot, dax3DirtySnapshot))
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        return "✅ 预设「$name」已保存"
    }

    /** 按名称加载预设 */
    fun loadPreset(context: android.content.Context, name: String): Boolean {
        val raw = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("name") == name) {
                _params.value = parseParamsFromJson(obj)
                // 恢复频段偏移量（预设若带频段数据则恢复,否则保持当前）
                if (obj.has("bandLeft") || obj.has("bandOffsets")) {
                    _bandOffsets.value = bandOffsetsFromJson(obj)
                }
                // 恢复 DAX3 系统属性
                if (obj.has("dax3")) {
                    val dax3Json = obj.getJSONObject("dax3")
                    val map = mutableMapOf<String, String>()
                    for (key in dax3Json.keys()) {
                        map[key] = dax3Json.getString(key)
                    }
                    _dax3.value = map
                }
                // 恢复 DAX3 dirty 标记
                _dax3Dirty.clear()
                if (obj.has("dax3Dirty")) {
                    val dirtyArr = obj.getJSONArray("dax3Dirty")
                    for (j in 0 until dirtyArr.length()) {
                        _dax3Dirty.add(dirtyArr.getString(j))
                    }
                }
                return true
            }
        }
        return false
    }

/**
 * 保存「用户 UI 当前看到的 Medium 场景最终绝对增益」.
 * 预设只存 Medium（三场景同一偏移 Δ 等比写回,存 Medium 即可;加载时用当前实时 Medium 基线倒推偏移）.
 * 结构:{left:[20], right:[20], surround:[4]}（下标对齐 ALL_BANDS / BASS_BANDS）
 */
private fun bandOffsetsToJson(band: BandOffsets): JSONObject {
    val base = _bandBaselines.value
    val medium = base.medium
    val obj = JSONObject()
    val leftArr = JSONArray(); val rightArr = JSONArray(); val surArr = JSONArray()
    for (i in ALL_BANDS.indices) {
        val freq = ALL_BANDS[i]
        val arr = medium[freq] ?: intArrayOf(0, 0, 0)
        leftArr.put(arr[0] + (band.left[freq] ?: 0))
        rightArr.put(arr[1] + (band.right[freq] ?: 0))
    }
    for (i in BASS_BANDS.indices) {
        val freq = BASS_BANDS[i]
        val arr = medium[freq] ?: intArrayOf(0, 0, 0)
        surArr.put(arr[2] + (band.surround[freq] ?: 0))
    }
    obj.put("left", leftArr)
    obj.put("right", rightArr)
    obj.put("surround", surArr)
    return obj
}

/**
 * 从预设 JSON 恢复频段偏移量（不改任何基线）.
 * 新格式（保存的 Medium 绝对显示值,left/right 为数组）:用「当前实时生效的 Medium 基线」倒推偏移 Δ,
 * 使 UI 显示==保存时值;偏移是真实相对当前基线的偏移,点"应用"三场景等比写回,无三场景 bug.
 * 旧格式（嵌套 {left/right:{freq:Δ}}）回退:直接作为偏移.
 */
private fun bandOffsetsFromJson(obj: JSONObject): BandOffsets {
    val band = BandOffsets()
    val data = if (obj.has("bandOffsets")) obj.getJSONObject("bandOffsets") else JSONObject()
    // 新格式:left 是 JSONArray（Medium 绝对显示值）
    if (data.optJSONArray("left") != null || data.optJSONArray("right") != null) {
        val curMedium = _bandBaselines.value.medium
        val leftArr = data.optJSONArray("left")
        val rightArr = data.optJSONArray("right")
        for (i in ALL_BANDS.indices) {
            val freq = ALL_BANDS[i]
            val baseL = curMedium[freq]?.get(0) ?: 0
            val baseR = curMedium[freq]?.get(1) ?: 0
            val l = if (leftArr != null && i < leftArr.length()) leftArr.getInt(i) else Int.MIN_VALUE
            val r = if (rightArr != null && i < rightArr.length()) rightArr.getInt(i) else Int.MIN_VALUE
            if (l != Int.MIN_VALUE) band.left[freq] = l - baseL
            if (r != Int.MIN_VALUE) band.right[freq] = r - baseR
        }
        val surArr = data.optJSONArray("surround")
        for (i in BASS_BANDS.indices) {
            val freq = BASS_BANDS[i]
            val baseS = curMedium[freq]?.get(2) ?: 0
            val s = if (surArr != null && i < surArr.length()) surArr.getInt(i) else Int.MIN_VALUE
            if (s != Int.MIN_VALUE) band.surround[freq] = s - baseS
        }
        return band
    }
    // 旧格式（相对偏移）:恢复为偏移
    fun readMapLegacy(parent: JSONObject, key: String): MutableMap<Int, Int> {
        val m = mutableMapOf<Int, Int>()
        try {
            if (parent.has(key)) {
                val o = parent.getJSONObject(key)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k.toInt()] = o.getInt(k)
                }
            }
        } catch (_: Exception) {}
        return m
    }
    band.left.putAll(readMapLegacy(data, "left"))
    band.right.putAll(readMapLegacy(data, "right"))
    band.surround.putAll(readMapLegacy(data, "surround"))
    return band
}

    /** 删除指定名称的预设 */
    fun deletePreset(context: android.content.Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("name") != name) {
                out.put(arr.getJSONObject(i))
            }
        }
        prefs.edit().putString(KEY_LIST, out.toString()).apply()
    }
    private fun buildPresetJson(name: String, p: DolbyParams, dax3Map: Map<String, String>, dax3DirtySet: Set<String>): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("dialogEnhancerEnable", p.dialogEnhancerEnable)
            put("dialogEnhancerAmount", p.dialogEnhancerAmount)
            put("dialogEnhancerDucking", p.dialogEnhancerDucking)
            put("bassEnhancerEnable", p.bassEnhancerEnable)
            put("virtualBassProcessEnable", p.virtualBassProcessEnable)
            put("surroundDecoderEnable", p.surroundDecoderEnable)
            put("surroundBoost", p.surroundBoost)
            put("volumeLevelerEnable", p.volumeLevelerEnable)
            put("virtualizerEnable", p.virtualizerEnable)
            put("virtualizerStartBand", p.virtualizerStartBand)
            put("miSurroundCompressorSteeringEnable", p.miSurroundCompressorSteeringEnable)
            put("calibrationBoost", p.calibrationBoost)
            put("ieqEnhanceEnable", p.ieqEnhanceEnable)
            put("volmaxBoost", p.volmaxBoost)
            put("peakValue", p.peakValue)
            put("hearingProtectionEnable", p.hearingProtectionEnable)
            put("bassExtractionEnable", p.bassExtractionEnable)
            put("bassEnhancerBoost", p.bassEnhancerBoost)
            put("bassEnhancerCutoffFrequency", p.bassEnhancerCutoffFrequency)
            put("virtualBassMode", p.virtualBassMode)
            put("virtualBassOverallGain", p.virtualBassOverallGain)
            put("virtualBassMixLow", p.virtualBassMixLow)
            put("virtualBassMixHigh", p.virtualBassMixHigh)
            put("bassExtractionCutoffFrequency", p.bassExtractionCutoffFrequency)
            put("regulatorStressOptimize", p.regulatorStressOptimize)
            // DAX3 系统属性
            val dax3Json = JSONObject()
            for ((k, v) in dax3Map) { dax3Json.put(k, v) }
            put("dax3", dax3Json)
            // DAX3 dirty 标记（加载时恢复,保证"应用"时只写这些 key）
            val dirtyArr = JSONArray()
            for (k in dax3DirtySet) { dirtyArr.put(k) }
            put("dax3Dirty", dirtyArr)
            // 频段偏移量（band_optimizer）一并保存
            put("bandOffsets", bandOffsetsToJson(_bandOffsets.value))
        }
    }

    private fun parseParamsFromJson(obj: JSONObject): DolbyParams {
        return DolbyParams(
            dialogEnhancerEnable = obj.optBoolean("dialogEnhancerEnable", true),
            dialogEnhancerAmount = obj.optInt("dialogEnhancerAmount", 5),
            dialogEnhancerDucking = obj.optInt("dialogEnhancerDucking", 0),
            bassEnhancerEnable = obj.optBoolean("bassEnhancerEnable", false),
            virtualBassProcessEnable = obj.optBoolean("virtualBassProcessEnable", false),
            surroundDecoderEnable = obj.optBoolean("surroundDecoderEnable", true),
            surroundBoost = obj.optInt("surroundBoost", 105),
            volumeLevelerEnable = obj.optBoolean("volumeLevelerEnable", true),
            virtualizerEnable = obj.optBoolean("virtualizerEnable", false),
            virtualizerStartBand = obj.optInt("virtualizerStartBand", 0),
            miSurroundCompressorSteeringEnable = obj.optBoolean("miSurroundCompressorSteeringEnable", false),
            calibrationBoost = obj.optInt("calibrationBoost", 0),
            ieqEnhanceEnable = obj.optBoolean("ieqEnhanceEnable", false),
            volmaxBoost = obj.optInt("volmaxBoost", 50),
            peakValue = obj.optInt("peakValue", 1024),
            hearingProtectionEnable = obj.optBoolean("hearingProtectionEnable", false),
            bassEnhancerBoost = obj.optInt("bassEnhancerBoost", 200),
            bassEnhancerCutoffFrequency = obj.optInt("bassEnhancerCutoffFrequency", 150),
            virtualBassMode = obj.optInt("virtualBassMode", 3),
            virtualBassOverallGain = obj.optInt("virtualBassOverallGain", 35),
            virtualBassMixLow = obj.optInt("virtualBassMixLow", 30),
            virtualBassMixHigh = obj.optInt("virtualBassMixHigh", 150),
        bassExtractionEnable = obj.optBoolean("bassExtractionEnable", false),
        bassExtractionCutoffFrequency = obj.optInt("bassExtractionCutoffFrequency", 65),
        regulatorStressOptimize = obj.optBoolean("regulatorStressOptimize", false)
    )
}
}