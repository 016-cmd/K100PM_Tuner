package com.k100pm.tuner.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.service.DolbyTunerManager
import com.k100pm.tuner.service.DolbyTunerManager.DolbyParams
import kotlinx.coroutines.launch

private val Accent = Color(0xFF22C55E)
private val AccentDim = Color(0xFF166534)
private val Danger = Color(0xFFEF4444)
private val Warning = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DolbyTunerScreen(activity: Activity) {
    val scope = rememberCoroutineScope()
    val params by DolbyTunerManager.params.collectAsState()
    val dax3 by DolbyTunerManager.dax3.collectAsState()
    val isLoading by DolbyTunerManager.isLoading.collectAsState()
    val isApplying by DolbyTunerManager.isApplying.collectAsState()
    val statusMsg by DolbyTunerManager.statusMsg.collectAsState()
    val resultMsg by DolbyTunerManager.resultMsg.collectAsState()
    val hasFactoryDax by DolbyTunerManager.hasFactoryDax.collectAsState()

    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    var presetNames by remember { mutableStateOf(listOf<String>()) }
    var saveName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFactoryWarnDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        DolbyTunerManager.loadParams(activity)
        // 检测 factory 目录并弹警告（会话期内只弹一次）
        if (!DolbyTunerManager.hasFactoryDax.value &&
            !DolbyTunerManager.isFactoryWarnShownThisSession()) {
            // 延迟一小段时间让 UI 渲染完成再弹窗
            kotlinx.coroutines.delay(500)
            showFactoryWarnDialog = true
            DolbyTunerManager.markFactoryWarnShown()
        }
    }

    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant
    val outline = colors.outlineVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("杜比调音台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            Text("Default · 扬声器", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
                AlertTipCard(statusMsg, isLoading, isApplying)

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                }

                if (!isLoading && statusMsg.isBlank() && !isApplying) {
                    SectionTitle("快速开关")

                    // 1. 对话增强
                    ExpandableCard(
                        title = "对话增强",
                        subtitle = "提升人声清晰度",
                        icon = Icons.Default.RecordVoiceOver,
                        enabled = params.dialogEnhancerEnable,
                        onToggle = { DolbyTunerManager.updateParams(params.copy(dialogEnhancerEnable = it)) }
                    ) {
                        ParamSlider("强度", params.dialogEnhancerAmount, 0..9) { DolbyTunerManager.updateParams(params.copy(dialogEnhancerAmount = it)) }
                        ParamSlider("回避量", params.dialogEnhancerDucking, 0..9) { DolbyTunerManager.updateParams(params.copy(dialogEnhancerDucking = it)) }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 2. 低音增强 BE
                    SimpleSwitchCard(
                        title = "低音增强",
                        subtitle = "Bass Enhancer",
                        icon = Icons.Default.Speed,
                        checked = params.bassEnhancerEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(bassEnhancerEnable = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 3. 虚拟低音 VB
                    SimpleSwitchCard(
                        title = "虚拟低音",
                        subtitle = "Virtual Bass",
                        icon = Icons.Default.Vibration,
                        checked = params.virtualBassProcessEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(virtualBassProcessEnable = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 4. 低频提取
                    ExpandableCard(
                        title = "低频提取",
                        subtitle = "Bass Extraction",
                        icon = Icons.Default.Tune,
                        enabled = params.bassExtractionEnable,
                        onToggle = { DolbyTunerManager.updateParams(params.copy(bassExtractionEnable = it)) }
                    ) {
                        ParamSlider("截止频率 (Hz)", params.bassExtractionCutoffFrequency, 60..90) {
                            DolbyTunerManager.updateParams(params.copy(bassExtractionCutoffFrequency = it))
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── DAX3 低音增强 ──
                    ExpandableCard(
                        title = "DAX3 低音增强",
                        subtitle = "系统属性层",
                        icon = Icons.Default.Speaker,
                        enabled = (dax3["persist.vendor.dolby.bass.enable"] ?: "1") == "1",
                        onToggle = {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.bass.enable",
                                if (it) "1" else "0"
                            )
                        }
                    ) {
                        ParamSlider(
                            "Boost",
                            (dax3["persist.vendor.dolby.bass.boost"] ?: "0").toIntOrNull() ?: 0,
                            0..16
                        ) {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.bass.boost",
                                it.toString()
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── DAX3 人声增强 ──
                    ExpandableCard(
                        title = "DAX3 人声增强",
                        subtitle = "系统属性层",
                        icon = Icons.Default.RecordVoiceOver,
                        enabled = (dax3["persist.vendor.dolby.dialog.enhancer.enable"] ?: "1") == "1",
                        onToggle = {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.dialog.enhancer.enable",
                                if (it) "1" else "0"
                            )
                        }
                    ) {
                        ParamSlider(
                            "强度",
                            (dax3["persist.vendor.dolby.dialog.enhancer.amount"] ?: "7").toIntOrNull() ?: 7,
                            0..9
                        ) {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.dialog.enhancer.amount",
                                it.toString()
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── DAX3 高频延伸 ──
                    ExpandableCard(
                        title = "DAX3 高频延伸",
                        subtitle = "系统属性层",
                        icon = Icons.Default.GraphicEq,
                        enabled = (dax3["persist.vendor.dolby.spectral.enable"] ?: "1") == "1",
                        onToggle = {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.spectral.enable",
                                if (it) "1" else "0"
                            )
                        }
                    ) {
                        ParamSlider(
                            "Boost",
                            (dax3["persist.vendor.dolby.spectral.boost"] ?: "7").toIntOrNull() ?: 7,
                            0..9
                        ) {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.spectral.boost",
                                it.toString()
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── DAX3 虚拟环绕（虚拟化音效强度）──
                    ExpandableCard(
                        title = "DAX3 虚拟环绕",
                        subtitle = "系统属性层",
                        icon = Icons.Default.SpatialAudio,
                        enabled = (dax3["persist.vendor.dolby.virtualizer.enable"] ?: "1") == "1",
                        onToggle = {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.virtualizer.enable",
                                if (it) "1" else "0"
                            )
                        }
                    ) {
                        ParamSlider(
                            "强度",
                            (dax3["persist.vendor.dolby.virtualizer.amount"] ?: "6").toIntOrNull() ?: 6,
                            0..16
                        ) {
                            DolbyTunerManager.updateDax3(
                                "persist.vendor.dolby.virtualizer.amount",
                                it.toString()
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 5. 环绕解码
                    ExpandableCard(
                        title = "环绕解码",
                        subtitle = "沉浸式环绕声",
                        icon = Icons.Default.SurroundSound,
                        enabled = params.surroundDecoderEnable,
                        onToggle = { DolbyTunerManager.updateParams(params.copy(surroundDecoderEnable = it)) }
                    ) {
                        ParamSlider("增强值", params.surroundBoost, 0..150) { DolbyTunerManager.updateParams(params.copy(surroundBoost = it)) }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 5. 音量均衡器
                    SimpleSwitchCard(
                        title = "音量均衡器",
                        subtitle = "稳定音量波动",
                        icon = Icons.Default.VolumeUp,
                        checked = params.volumeLevelerEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(volumeLevelerEnable = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 6. 虚拟器
                    ExpandableCard(
                        title = "虚拟器",
                        subtitle = "空间音效扩展",
                        icon = Icons.Default.SpatialAudioOff,
                        enabled = params.virtualizerEnable,
                        onToggle = { DolbyTunerManager.updateParams(params.copy(virtualizerEnable = it)) }
                    ) {
                        ParamSlider("起始频段", params.virtualizerStartBand, 0..13) { DolbyTunerManager.updateParams(params.copy(virtualizerStartBand = it)) }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 6.1 环绕压缩器舵向
                    SimpleSwitchCard(
                        title = "环绕压缩器舵向",
                        subtitle = "增强环绕声场方向感与定位",
                        icon = Icons.Default.GraphicEq,
                        checked = params.miSurroundCompressorSteeringEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(miSurroundCompressorSteeringEnable = it)) }
                    )

                    Spacer(Modifier.height(6.dp))
                    
                    // 6.4 动态范围优化
                    SimpleSwitchCard(
                        title = "动态范围优化",
                        subtitle = "提升输出压力限制,增加响度",
                        icon = Icons.Default.TrendingUp,
                        checked = params.regulatorStressOptimize,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(regulatorStressOptimize = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 6.5 IEQ 智能均衡增强（杜比智能均衡曲线,模块已写死）
                    SimpleSwitchCard(
                        title = "IEQ 智能均衡",
                        subtitle = "开启后启用杜比智能均衡增强曲线",
                        icon = Icons.Default.Equalizer,
                        checked = params.ieqEnhanceEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(ieqEnhanceEnable = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 7. 校准增益
                    SimpleSliderCard(
                        title = "校准增益",
                        subtitle = "基础增益校准",
                        icon = Icons.Default.Tune,
                        value = params.calibrationBoost,
                        range = 0..64,
                        suffix = "",
                        onValueChange = { DolbyTunerManager.updateParams(params.copy(calibrationBoost = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 8. 最大音量增强
                    SimpleSliderCard(
                        title = "最大音量增强",
                        subtitle = "提升音量上限",
                        icon = Icons.Default.VolumeUp,
                        value = params.volmaxBoost,
                        range = 0..96,
                        suffix = "",
                        onValueChange = { DolbyTunerManager.updateParams(params.copy(volmaxBoost = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 9. 峰值
                    SimpleSliderCard(
                        title = "峰值",
                        subtitle = "动态峰值控制",
                        icon = Icons.Default.TrendingUp,
                        value = params.peakValue,
                        range = 256..1024,
                        suffix = "",
                        onValueChange = { DolbyTunerManager.updateParams(params.copy(peakValue = it)) }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 10. 听力保护
                    SimpleSwitchCard(
                        title = "听力保护",
                        subtitle = "限制过高音量",
                        icon = Icons.Default.Headphones,
                        checked = params.hearingProtectionEnable,
                        onCheckedChange = { DolbyTunerManager.updateParams(params.copy(hearingProtectionEnable = it)) }
                    )

                    Spacer(Modifier.height(12.dp))

                    // ── 高级区 ──
                    SectionTitle("高级调谐")

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AccentDim.copy(alpha = 0.2f))
                                    .then(Modifier.clickable { showAdvanced = !showAdvanced })
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("BE / VB 精细参数", color = onSurface, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Icon(
                                    if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null, tint = onSurfaceVariant, modifier = Modifier.size(20.dp)
                                )
                            }

                            AnimatedVisibility(visible = showAdvanced) {
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    Divider(color = outline)
                                    Spacer(Modifier.height(4.dp))

                                    Text("低音增强(BE) 精细", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    ParamSlider("Boost", params.bassEnhancerBoost, 0..192) { DolbyTunerManager.updateParams(params.copy(bassEnhancerBoost = it)) }
                                    ParamSlider("Cutoff 频率", params.bassEnhancerCutoffFrequency, 80..200) { DolbyTunerManager.updateParams(params.copy(bassEnhancerCutoffFrequency = it)) }

                                    Spacer(Modifier.height(8.dp))
                                    Divider(color = outline)
                                    Spacer(Modifier.height(4.dp))

                                    Text("虚拟低音(VB) 精细", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    ParamDropdown("模式", params.virtualBassMode, 0..6) { DolbyTunerManager.updateParams(params.copy(virtualBassMode = it)) }
                                    ParamSlider("总增益", params.virtualBassOverallGain, 0..80) { DolbyTunerManager.updateParams(params.copy(virtualBassOverallGain = it)) }
                                    ParamSlider("混合低频 (Hz)", params.virtualBassMixLow, 30..80) { DolbyTunerManager.updateParams(params.copy(virtualBassMixLow = it)) }
                                    ParamSlider("混合高频 (Hz)", params.virtualBassMixHigh, 80..350) { DolbyTunerManager.updateParams(params.copy(virtualBassMixHigh = it)) }

                                    Spacer(Modifier.height(4.dp))
                                    Text("⚠ 三场景(large/medium/small)同步写入",
                                        color = onSurfaceVariant.copy(alpha = 0.6f), fontSize = 9.sp)
                                }
                            }
                        }
                    }

                    // ── 频段调节（band_optimizer,Medium基准·三场景同步） ──
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("频段调节")
                    BandTunerSection()

                    Spacer(Modifier.height(16.dp))

                    // ── 应用 & 重置 按钮 ──
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                confirmAction = "reset"
                                showConfirmDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (hasFactoryDax) Danger else Danger.copy(alpha = 0.3f)
                            ),
                            enabled = !isApplying && hasFactoryDax
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重置为模块默认", fontSize = 12.sp,
                                color = if (hasFactoryDax) Color.Unspecified else Danger.copy(alpha = 0.3f))
                        }

                        Button(
                            onClick = {
                                confirmAction = "apply"
                                showConfirmDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            enabled = !isApplying
                        ) {
                            if (isApplying) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("应用更改", fontSize = 12.sp)
                            }
                        }
                    }

                    // ── 温馨提示卡 ──
Card(
modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
shape = RoundedCornerShape(12.dp),
colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f))
) {
Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
Icon(Icons.Default.Info, null, tint = Warning, modifier = Modifier.size(18.dp))
Spacer(Modifier.width(8.dp))
Column(Modifier.weight(1f)) {
Text("💡 温馨提示", color = Warning, fontWeight = FontWeight.Bold, fontSize = 12.sp)
Spacer(Modifier.height(4.dp))
Text(
"参数修改后将自动重启音频服务,效果即时生效,无需重启手机.\n\n" +
"⚠ 请不要频繁应用参数,每次应用都需要重启音频服务,频繁重启可能导致音频服务出现问题.\n\n" +
"🔄 「校准增益」和「最大音量上限」修改后需要重启手机才会生效,其余参数即时生效.",
color = onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp
)
}
}
}

                    Spacer(Modifier.height(12.dp))

                    // ── 预设保存/加载 ──
                    SectionTitle("预设")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showSaveDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                            ) {
                                Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存预设", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    presetNames = DolbyTunerManager.getPresetNames(activity)
                                    showLoadDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                            ) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("加载预设", fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
    }

    // ── 保存预设弹窗 ──
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                showSaveDialog = false
                saveName = ""
            },
            title = { Text("保存预设", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("输入预设名称（最多5个预设）", fontSize = 13.sp, color = onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (saveName.isNotBlank()) {
                            val msg = DolbyTunerManager.savePreset(activity, saveName.trim())
                            DolbyTunerManager.setResultMsg(msg)
                            saveName = ""
                            showSaveDialog = false
                        }
                    },
                    enabled = saveName.isNotBlank()
                ) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    saveName = ""
                }) { Text("取消") }
            }
        )
    }

    // ── 加载预设弹窗 ──
    if (showLoadDialog) {
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = { Text("加载预设", fontWeight = FontWeight.Bold) },
            text = {
                if (presetNames.isEmpty()) {
                    Text("暂无保存的预设", color = onSurfaceVariant, fontSize = 13.sp)
                } else {
                    Column {
                        presetNames.forEach { name ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, modifier = Modifier.weight(1f), fontSize = 13.sp, color = onSurface)
                                    IconButton(
                                        onClick = {
                                            DolbyTunerManager.loadPreset(activity, name)
                                            DolbyTunerManager.setResultMsg("✅ 预设「$name」已加载")
                                            showLoadDialog = false
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteTarget = name
                                            showDeleteConfirm = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = Danger, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLoadDialog = false }) { Text("关闭") }
            }
        )
    }

    // ── 删除预设确认 ──
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除预设", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除预设「$deleteTarget」?", fontSize = 13.sp) },
            confirmButton = {
TextButton(onClick = {
                                    DolbyTunerManager.deletePreset(activity, deleteTarget)
                                    DolbyTunerManager.setResultMsg("✅ 预设「$deleteTarget」已删除")
                                    presetNames = DolbyTunerManager.getPresetNames(activity)
                                    deleteTarget = ""
                                    showDeleteConfirm = false
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // ── 二次确认弹窗（应用/重置） ──
    if (showConfirmDialog) {
        val isReset = confirmAction == "reset"
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(if (isReset) "确认重置" else "确认应用", fontWeight = FontWeight.Bold) },
            text = {
Text(
if (isReset)
"将恢复扬声器所有杜比参数为模块原始值.\n音频服务将自动重启,效果即时生效."
else
"将写入当前杜比参数到模块目录,音频服务将自动重启,效果即时生效.\n\n⚠ 请不要频繁应用参数,每次应用都需要重启音频服务,频繁重启可能导致音频服务出现问题.",
fontSize = 13.sp
)
},
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    scope.launch {
                        if (isReset) {
                            DolbyTunerManager.resetToModuleDefault(activity)
                        } else {
                            DolbyTunerManager.applyChanges(activity)
                        }
                    }
                }) {
                    Text("确定", color = if (isReset) Danger else Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 操作结果弹窗（应用/重置成功或失败） ──
    if (resultMsg.isNotBlank()) {
        val isError = resultMsg.startsWith("❌")
        AlertDialog(
            onDismissRequest = { DolbyTunerManager.clearResultMsg() },
            title = {
                Text(
                    if (isError) "操作失败" else "操作成功",
                    fontWeight = FontWeight.Bold,
                    color = if (isError) Danger else Accent
                )
            },
            text = { Text(resultMsg, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { DolbyTunerManager.clearResultMsg() }) {
                    Text("确定", color = if (isError) Danger else Accent)
                }
            }
        )
    }

    // ── factory 缺失警告弹窗（旧版模块无重置文件） ──
    if (showFactoryWarnDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryWarnDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Warning) },
            title = { Text("当前模块不支持一键重置", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "当前模块版本较旧,缺少出厂原始调音文件,无法使用一键重置功能.\n\n" +
                    "建议:请先在调音台中调整好参数后,点击「保存预设」手动保存一份作为恢复点.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showFactoryWarnDialog = false }) {
                    Text("知道了", color = Warning)
                }
            }
        )
    }
}

// ═══════════════════════════════════════════
//  UI 组件
// ═══════════════════════════════════════════

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun AlertTipCard(statusMsg: String, isLoading: Boolean, isApplying: Boolean) {
    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant
    
    if (statusMsg.isNotBlank()) {
        val isError = statusMsg.contains("失败", ignoreCase = true) || statusMsg.contains("Error", ignoreCase = true)
        val isSuccess = statusMsg.contains("成功", ignoreCase = true)
        val bgColor = when {
            isError -> Danger.copy(alpha = 0.15f)
            isSuccess -> AccentDim.copy(alpha = 0.5f)
            else -> Warning.copy(alpha = 0.12f)
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when { isError -> Icons.Default.Error; isSuccess -> Icons.Default.CheckCircle; else -> Icons.Default.Info },
                    null, tint = if (isError) Danger else if (isSuccess) Accent else Warning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(statusMsg, color = onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
    if (isApplying) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AccentDim.copy(alpha = 0.4f))
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在应用参数...", color = onSurface, fontSize = 13.sp)
            }
        }
    }
}

/**
 * 带展开详细参数的卡片（如对话增强、虚拟器）
 */
@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant
    val outline = colors.outlineVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle(it) },
                    modifier = Modifier.scale(0.7f),
                    colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.25f))
                )
                Spacer(Modifier.width(4.dp))
                Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) Accent else Color.Gray)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = onSurfaceVariant, fontSize = 10.sp)
                }
                if (enabled) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, tint = onSurfaceVariant, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded && enabled) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
                    Divider(color = outline)
                    Spacer(Modifier.height(2.dp))
                    content()
                }
            }
        }
    }
}

/**
 * 简单开关卡片
 */
@Composable
private fun SimpleSwitchCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.7f),
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.25f))
            )
            Spacer(Modifier.width(4.dp))
            Icon(icon, null, Modifier.size(18.dp), tint = if (checked) Accent else Color.Gray)
            Spacer(Modifier.width(6.dp))
                Column {
                    Text(title, color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = onSurfaceVariant, fontSize = 10.sp)
                }
        }
    }
}

/**
 * 带滑块的简单卡片
 */
@Composable
private fun SimpleSliderCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: Int,
    range: IntRange,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val onSurface = colors.onSurface
    val onSurfaceVariant = colors.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = Accent)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = onSurfaceVariant, fontSize = 10.sp)
                }
                Text(
                    "$value$suffix",
                    color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End, modifier = Modifier.width(40.dp)
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Accent.copy(alpha = 0.15f)
                )
            )
        }
    }
}

/**
 * 滑块参数（用于展开区域内）
 */
@Composable
private fun ParamSlider(
    label: String,
    value: Int,
    range: IntRange,
    onUpdate: (Int) -> Unit
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), color = onSurfaceVariant, fontSize = 11.sp)
        Slider(
            value = value.toFloat(),
            onValueChange = { onUpdate(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Accent.copy(alpha = 0.1f))
        )
        Text(value.toString(), Modifier.width(32.dp), color = Accent, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}

/**
 * 下拉选择参数（用于展开区域内,如VB模式）
 */
@Composable
private fun ParamDropdown(
    label: String,
    value: Int,
    range: IntRange,
    onUpdate: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), color = onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(AccentDim.copy(alpha = 0.35f))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("模式 $value", color = Accent, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null, tint = Accent, modifier = Modifier.size(16.dp))
            }
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        range.forEach { v ->
            DropdownMenuItem(
                text = { Text("模式 $v") },
                onClick = {
                    onUpdate(v)
                    expanded = false
                }
            )
        }
    }
}