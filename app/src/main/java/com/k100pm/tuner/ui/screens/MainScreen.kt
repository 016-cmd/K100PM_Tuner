package com.k100pm.tuner.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k100pm.tuner.ui.MainViewModel
import com.k100pm.tuner.ui.components.ControlPanel
import com.k100pm.tuner.ui.components.ModuleStatusCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: Activity,
    viewModel: MainViewModel = viewModel()
) {
    var showSettings by remember { mutableStateOf(false) }
    val moduleStatus by viewModel.moduleStatus.collectAsState()
    val hasRoot by viewModel.hasRoot.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val canEdit = viewModel.canEdit
    val appVersion = remember {
        try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ⚡ 轮询只在「主页」处于激活/可见时才运行：
    //   - 进入主页 tab (MainScreen 进入组合) → 启动寄存器自动刷新
    //   - 切换到其他 tab 或退出 APP (MainScreen 移出组合) → DisposableEffect onDispose → 停止轮询
    //   以此避免后台/切走时仍每 2 秒高频 su 轮询寄存器造成耗电。
    LaunchedEffect(Unit) { viewModel.onMainScreenVisible(true) }
    DisposableEffect(Unit) { onDispose { viewModel.onMainScreenVisible(false) } }

    if (showSettings) {
        SettingsScreen(activity = activity, onBack = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("K100PM Tuner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("WSA 内核寄存器实时控制", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ModuleStatusCard(
                status = moduleStatus,
                hasRoot = hasRoot,
                onRefresh = { viewModel.requestRootAndDetect() },
                onActivate = { viewModel.requestRootAndDetect() }
            )

            Spacer(Modifier.height(4.dp))

            if (canEdit || !moduleStatus.isChecking) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
            }

            if (isRefreshing) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取寄存器值...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                ControlPanel(viewModel = viewModel, modifier = Modifier.padding(bottom = 32.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "K100PM Tuner v$appVersion · by 016.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}