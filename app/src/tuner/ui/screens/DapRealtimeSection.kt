package com.k100pm.tuner.ui.screens
import com.k100pm.tuner.ui.components.fmtFreq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.service.DapCurveStore
import com.k100pm.tuner.service.DapRealtime
import com.k100pm.tuner.ui.components.BandCurveCanvas
import com.k100pm.tuner.ui.components.DapBandSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RAccent: Color get() = com.k100pm.tuner.ui.theme.Appearance.accent
private val RAccentDim: Color get() = com.k100pm.tuner.ui.theme.Appearance.accent
private val RDanger = Color(0xFFEF4444)
private val RWarning = Color(0xFFF59E0B)

/**
 * Dolby 实时层（GEQ 20 段）
 *
 * 与「文件层」的区别：
 *   实时层 → 直接写运行中的 Dolby DAP（参数 110），不重启音频服务
 *   文件层 → 改模块 dax-default.xml，应用后重启音频服务
 *
 * 「保存到文件」做三件事：
 *   ① 写全部 profile（换设备/切 profile 也不丢）
 *   ② 发 0x09000000 让 Dolby 自己落库（重启后它自己读回）
 *   ③ 存一份 JSON 到 APP 内部目录，开机由 DapBootReceiver 兜底重放
 */
@Composable
fun DapRealtimeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var curve by remember { mutableStateOf(IntArray(DapRealtime.BANDS)) }
    var info by remember { mutableStateOf<DapRealtime.Info?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var savedAt by remember { mutableStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }   // 默认收起，点标题再展开
    var source by remember { mutableStateOf("") }
    var lastRefreshAt by remember { mutableStateOf(0L) }
    var showSavedDialog by remember { mutableStateOf(false) }

    val liveJob = remember { mutableStateOf<Job?>(null) }
    val bandListState = rememberLazyListState()

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant

    suspend fun refreshFromDevice() {
        // 只有第一次（还没有来源标记）才显示转圈，避免从别的页面回来的闪烁
        if (source.isBlank()) loading = true
        msg = ""
        val i = withContext(Dispatchers.IO) {
            val info = DapRealtime.inspect()
            if (info.available) {
                // 打开常驻会话：拖动期间复用同一个 effect，避免反复 release 丢参数
                DapRealtime.openSession()
            }
            info
        }
        info = i

        // ① 从 DSP 读回真实曲线（当前 profile → 其它 profile）
        val live = withContext(Dispatchers.IO) {
            if (i.available) DapRealtime.readLive() else null
        }
        // ② 草稿（拖动过但没点保存）　③ 正式存档（点过保存到文件）
        val draft = withContext(Dispatchers.IO) { DapCurveStore.loadDraft(context) }
        val saved = DapCurveStore.load(context)

        val liveHasValue = live != null && live.any { it != 0 }
        val alt = draft ?: saved
        val altHasValue = alt != null && alt.any { it != 0 }

        // ── 修复「重进 APP 声音被重置」──
        // 拖动写的参数只在 Dolby 的内存里；effect 实例一释放（退出 APP / 重建）就丢，
        // 而草稿还在。重进时如果 DSP 的值和草稿不一致，就把草稿重新写回去，
        // 保证「听到的」和「看到的」永远一致。
        val restored: IntArray? = if (draft != null && (live == null || !draft.contentEquals(live))) {
            val err = withContext(Dispatchers.IO) { DapRealtime.writeLive(draft) }
            if (err == null) draft else null
        } else {
            null
        }

        when {
            // 草稿比 DSP 新 → 已写回，以草稿为准
            restored != null -> {
                curve = restored
                source = "已恢复上次未保存的调音"
            }
            // DSP 读到的就是当前真实曲线，最可信
            liveHasValue -> {
                curve = live!!
                source = "DSP 实时值"
            }
            // DSP 读到全 0，但本地有非 0 记录 → 读数不可靠（或刚开机没应用），用本地记录兜底
            live != null && altHasValue -> {
                curve = alt!!
                source = if (draft != null) "本地草稿（DSP 读到全 0）" else "已保存文件（DSP 读到全 0）"
            }
            // DSP 平直且本地也没记录 → 就是平直
            live != null -> {
                curve = live
                source = "DSP 实时值（平直）"
            }
            draft != null -> {
                curve = draft
                source = "未保存的草稿"
            }
            saved != null -> {
                curve = saved
                source = "已保存文件"
            }
            else -> {
                source = if (i.available) "无记录（平直）" else "不可用"
            }
        }

        savedAt = DapCurveStore.savedAt(context)
        if (!i.available) msg = i.detail
        lastRefreshAt = System.currentTimeMillis()
        loading = false
    }

    LaunchedEffect(Unit) { refreshFromDevice() }

    // 预设加载 / 重启音频服务写回 / 其它模块改动 DSP → 收到通知后重新读一次，UI 跟着变
    val reloadTick by DapRealtime.reloadTick.collectAsState()
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) {
            refreshFromDevice()
            source = "已同步 · " + source
        }
    }

    // 每次回到前台（切回 APP / 从别的页面回来）重新读一次，保证 UI 跟 DSP 一致
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // 3 秒内刚读过就不重复读，减少对 audioserver 的反复查询
                if (System.currentTimeMillis() - lastRefreshAt > 3_000L) {
                    scope.launch { refreshFromDevice() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 不主动关会话：效果实例一 release，拖动写进去但没落库的参数就会丢，
// 表现为「退出去再进来声音变回原样」。这里让它跟进程同寿命。
    DisposableEffect(Unit) {
        onDispose { /* 保留 session */ }
    }

    // 拖动时实时写（全部 profile，常开会话，约 10~20ms），节流 90ms
    fun pushLive(newCurve: IntArray) {
        curve = newCurve
        source = "正在拖动（实时写入 DSP）"
        liveJob.value?.cancel()
        liveJob.value = scope.launch {
            delay(90)
            val err = withContext(Dispatchers.IO) {
                val e = DapRealtime.writeLive(newCurve)
                // 顺手存一份草稿：没点「保存到文件」就退出，重进页面时 UI 也能显示上次调过的样子
                if (e == null) DapCurveStore.saveDraft(context, newCurve)
                e
            }
            if (err != null) {
                msg = err
                source = "写入失败"
            } else {
                source = "实时值（未保存）"
            }
        }
    }

    fun saveToFile() {
        busy = true
        scope.launch {
            val err = withContext(Dispatchers.IO) { DapRealtime.commit(curve) }
            if (err == null) {
                DapCurveStore.save(context, curve, bootApply = true)
                DapCurveStore.clearDraft(context)
                savedAt = DapCurveStore.savedAt(context)
                source = "已保存文件"
                msg = "✅ 已保存到文件 · 重启手机后会自动写回（未重启音频服务）"
                showSavedDialog = true
            } else {
                msg = "❌ $err"
            }
            busy = false
        }
    }

    com.k100pm.tuner.ui.components.GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {

            // ── 标题行 ──
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(RAccentDim.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = RAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Dolby · Realtime Layer",
                        color = onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Text(
                        "实时层 · GEQ 20 段 · 不重启音频服务",
                        color = onSurfaceVariant, fontSize = 10.sp
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = onSurfaceVariant, modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (loading) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = RAccent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                return@Column
            }

            if (info?.available == false) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ ${info?.detail ?: "实时层不可用"}",
                    color = RWarning, fontSize = 11.sp, lineHeight = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "文件层功能不受影响，可以继续使用。",
                    color = onSurfaceVariant, fontSize = 10.sp
                )
                return@Column
            }

            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth()) {

                    Spacer(Modifier.height(6.dp))

                    // ── 能力状态 ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            info?.detail ?: "",
                            color = RAccent.copy(alpha = 0.9f), fontSize = 10.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (info != null) {
                            Text(
                                "profile ${info!!.profileCount} · 当前 ${info!!.currentProfile}",
                                color = onSurfaceVariant, fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── 曲线预览（折线）──
                    val pts = DapRealtime.FREQS.mapIndexed { i, f -> f to curve[i] }
                    BandCurveCanvas(points = pts, lineColor = RAccent, showDelta = false)
                    Text(
                        "📈 运行中的 GEQ 曲线 · 来源：" + (if (source.isBlank()) "未知" else source),
                        color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 9.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    Divider(color = outline)
                    Spacer(Modifier.height(6.dp))

                    // ── 20 段拉条（横向排列，左右滑动）──
                    LazyRow(
                        state = bandListState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(DapRealtime.FREQS.toList()) { index: Int, freq: Int ->
                            val q4 = curve[index]
                            DapBandSlider(
                                freqLabel = fmtFreq(freq),
                                valueQ4 = q4,
                                trackHeight = 240.dp,
                                accent = RAccent,
                                trackColor = outline.copy(alpha = 0.30f),
                                tickColor = onSurfaceVariant,
                                valueColor = onSurfaceVariant,
                                freqColor = onSurfaceVariant,
                                onChange = { v ->
                                    val next = curve.copyOf()
                                    next[index] = v
                                    pushLive(next)
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Swipe, null,
                            tint = onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "左右滑动查看 20 段 · 上下拖动调节 · 双击某段归零",
                            color = onSurfaceVariant.copy(alpha = 0.55f), fontSize = 9.sp
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "单位：dB（1 段 = 1/16 dB）。合计建议不超过 +24 dB，超过 +10 dB 会标黄提示。",
                        color = onSurfaceVariant.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 13.sp
                    )

                    Spacer(Modifier.height(10.dp))
                    Divider(color = outline)
                    Spacer(Modifier.height(8.dp))

                    // ── 按钮 ──
                    Button(
                        onClick = { saveToFile() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RAccent),
                        enabled = !busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("保存到文件（仅GEQ）", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "仅写入实时层 GEQ · 不重启音频服务 · 重启手机后仍生效",
                        color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 9.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { refreshFromDevice() } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RAccent)
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("读取当前", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val flat = IntArray(DapRealtime.BANDS)
                                pushLive(flat)
                                msg = "已置为平直曲线（点「保存到文件」才会持久化）"
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RWarning)
                        ) {
                            Icon(Icons.Default.HorizontalRule, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重置平直", fontSize = 11.sp)
                        }
                    }

                    // 读不到曲线时点这里，把每一步状态码打出来（诊断用）
                    TextButton(
                        onClick = {
                            scope.launch {
                                val p = withContext(Dispatchers.IO) { DapRealtime.probeRead() }
                                msg = p
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("读不到实时值？点这里诊断", fontSize = 9.sp, color = onSurfaceVariant)
                    }

                    if (savedAt > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "上次保存：" + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(savedAt)),
                            color = onSurfaceVariant.copy(alpha = 0.6f), fontSize = 9.sp
                        )
                    }

                    if (msg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            msg,
                            color = if (msg.startsWith("✅")) RAccent else RWarning,
                            fontSize = 10.sp, lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }

    // ── 保存成功弹窗（和文件层「音频服务已重启」那种一样是确认式的）──
    if (showSavedDialog) {
        AlertDialog(
            onDismissRequest = { showSavedDialog = false },
            title = { Text("已保存到杜比", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        "GEQ 20 段已写入：",
                        fontSize = 12.sp, color = onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "① 杜比引擎内存（立刻生效）\n" +
                            "② Dolby 数据库 /data/vendor/dolby/dax_sqlite3.db\n" +
                            "③ APP 存档（开机写回用）",
                        fontSize = 11.sp, color = onSurfaceVariant, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "重启手机后会继续沿用这次保存的曲线。\n本次只写 GEQ，没有重启音频服务，也不影响下面的文件层。",
                        fontSize = 11.sp, color = RAccent, lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavedDialog = false }) {
                    Text("知道了", color = RAccent, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}