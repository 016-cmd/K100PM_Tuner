package com.k100pm.tuner.v4a2.ui.screens.main

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.k100pm.tuner.R
import com.k100pm.tuner.v4a2.effect.EffectState
import com.k100pm.tuner.v4a2.ui.components.LocalInteractionEnabled
import com.k100pm.tuner.v4a2.ui.screens.debug.DebugLogDialog
import com.k100pm.tuner.v4a2.ui.screens.device.DeviceDialog
import com.k100pm.tuner.v4a2.ui.screens.preset.PresetDialog
import com.k100pm.tuner.v4a2.ui.screens.settings.SettingsDialog
import com.k100pm.tuner.v4a2.ui.screens.status.DriverStatusDialog
import com.k100pm.tuner.v4a2.ui.theme.master_on_container_dark
import com.k100pm.tuner.v4a2.ui.theme.master_on_container_light
import com.k100pm.tuner.v4a2.ui.theme.master_on_onContainer_dark
import com.k100pm.tuner.v4a2.ui.theme.master_on_onContainer_light
import com.k100pm.tuner.v4a2.ui.theme.status_active_green
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.saveSettingsOnBackground()
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val presets by viewModel.presetList.collectAsStateWithLifecycle()
    val deviceSettings by viewModel.deviceSettingsList.collectAsStateWithLifecycle()
    val driverStatus by viewModel.driverStatus.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStartEnabled.collectAsStateWithLifecycle()
    val globalMode by viewModel.globalModeEnabled.collectAsStateWithLifecycle()
    val aidlMode by viewModel.aidlModeEnabled.collectAsStateWithLifecycle()
    val debugMode by viewModel.debugModeEnabled.collectAsStateWithLifecycle()

    var showPresetDialog by remember { mutableStateOf(false) }
    var showDriverStatusDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDebugLog by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val appVersionName =
        remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }

    val clearAllProgressStr = stringResource(R.string.preset_clear_all_progress)
    val clearedStr = stringResource(R.string.preset_cleared)

    if (showPresetDialog) {
        PresetDialog(
            presets = presets,
            onSave = viewModel::savePreset,
            onLoad = { id ->
                viewModel.loadPreset(id)
                showPresetDialog = false
            },
            onDelete = viewModel::deletePreset,
            onRename = viewModel::renamePreset,
            onUpdate = viewModel::updatePreset,
            onClearAll = {
                viewModel.clearAllPresets(
                    notificationTitle = clearAllProgressStr,
                    successStr = clearedStr,
                ) { count ->
                    Toast.makeText(context, "$clearedStr: $count", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showPresetDialog = false },
        )
    }

    if (showDriverStatusDialog) {
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.queryDriverStatus()
                delay(500)
            }
        }
        DriverStatusDialog(
            driverStatus = driverStatus,
            onDismiss = { showDriverStatusDialog = false },
        )
    }

    if (showDebugLog) {
        DebugLogDialog(
            onDisableDebug = {
                viewModel.disableDebugMode()
                showDebugLog = false
            },
            onDismiss = { showDebugLog = false },
        )
    }

    if (showDeviceDialog) {
        DeviceDialog(
            devices = deviceSettings,
            activeDeviceId = state.activeDeviceId,
            onRename = viewModel::renameDevice,
            onLoad = viewModel::loadDevicePreset,
            onUpdate = viewModel::saveDevicePreset,
            onDelete = viewModel::deleteDeviceSettings,
            onDismiss = { showDeviceDialog = false },
        )
    }

    if (showResetConfirm) {
        val resetTitle = stringResource(R.string.reset_confirm_title)
        val resetMessage = stringResource(R.string.reset_confirm_message)
        val confirmStr = stringResource(R.string.action_confirm)
        val cancelStr = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(resetTitle) },
            text = { Text(resetMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetToDefaults()
                    },
                ) { Text(confirmStr) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(cancelStr) }
            },
        )
    }

    val importSuccessStr = stringResource(R.string.import_success)
    val importFailedStr = stringResource(R.string.import_failed)
    val importPresetStr = stringResource(R.string.settings_import_preset)
    val importPresetLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importPresetFiles(uris, notificationTitle = importPresetStr, successStr = importSuccessStr) { success ->
                    val msg = if (success) importSuccessStr else importFailedStr
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    val importKernelStr = stringResource(R.string.settings_import_kernel)
    val importKernelLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importKernels(uris, notificationTitle = importKernelStr, successStr = importSuccessStr) { success ->
                    val msg = if (success) importSuccessStr else importFailedStr
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    val importVdcStr = stringResource(R.string.settings_import_vdc)
    val importVdcLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importVdcs(uris, notificationTitle = importVdcStr, successStr = importSuccessStr) { success ->
                    val msg = if (success) importSuccessStr else importFailedStr
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    if (showSettingsDialog) {
        LaunchedEffect(Unit) { viewModel.queryDriverStatus() }
        SettingsDialog(
            autoStartEnabled = autoStart,
            globalModeEnabled = globalMode,
            aidlModeActive = aidlMode,
            onGlobalModeChanged = viewModel::toggleGlobalMode,
            driverStatus = driverStatus,
            appVersionName = appVersionName,
            onAutoStartChanged = viewModel::toggleAutoStart,
            onImportPreset = { importPresetLauncher.launch(arrayOf("application/json", "*/*")) },
            onImportKernel = {
                importKernelLauncher.launch(
                    arrayOf(
                        "audio/*",
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            },
            onDebugUnlocked = viewModel::enableDebugMode,
            onImportVdc = { importVdcLauncher.launch(arrayOf("*/*")) },
            onDismiss = { showSettingsDialog = false },
        )
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        val deviceName = state.activeDeviceName
                        if (deviceName.isNotEmpty()) {
                            val dotColor =
                                if (state.masterEnable) {
                                    status_active_green
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Canvas(modifier = Modifier.size(5.dp)) {
                                    drawCircle(dotColor)
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = deviceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                actions = {
                    if (debugMode) {
                        IconButton(onClick = { showDebugLog = true }) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = stringResource(R.string.debug_log_title),
                            )
                        }
                    }
                    IconButton(onClick = { showDeviceDialog = true }) {
                        Icon(
                            Icons.Filled.SpeakerGroup,
                            contentDescription = stringResource(R.string.menu_devices),
                        )
                    }
                    IconButton(onClick = { showDriverStatusDialog = true }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.menu_driver_status),
                        )
                    }
                    IconButton(onClick = { showPresetDialog = true }) {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = stringResource(R.string.menu_presets),
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.menu_settings),
                        )
                    }
                },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { paddingValues ->
        EffectList(
            state = state,
            viewModel = viewModel,
            onResetDefaults = { showResetConfirm = true },
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/** 页面顶部的 V4A 主总开关卡片（避免右下角 FAB 被底部 dock 遮挡）。横排：左=总开关，右=恢复默认。 */
@Composable
private fun MasterToggleCard(
    state: EffectState,
    onToggle: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    val masterOn = state.masterEnable
    val darkTheme = isSystemInDarkTheme()
    val containerColor =
        when {
            !masterOn -> MaterialTheme.colorScheme.errorContainer
            darkTheme -> master_on_container_dark
            else -> master_on_container_light
        }
    val onContainerColor =
        when {
            !masterOn -> MaterialTheme.colorScheme.onErrorContainer
            darkTheme -> master_on_onContainer_dark
            else -> master_on_onContainer_light
        }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = onContainerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左：总开关（整区可点击切换；去掉 ripple 白块）
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.master_enable), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = masterOn, onCheckedChange = null, enabled = false)
            }
            // 右：恢复默认（点击弹确认框）
            TextButton(onClick = onResetDefaults) {
                Text(
                    text = stringResource(R.string.reset_to_defaults),
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainerColor,
                )
            }
        }
    }
}

@Composable
private fun EffectList(
    state: EffectState,
    viewModel: MainViewModel,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (state.masterEnable) 1f else 0.38f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "effectListAlpha",
    )
    val interactive = state.masterEnable
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 45.dp
    LazyColumn(
        modifier = modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
        contentPadding = PaddingValues(bottom = navBottom),
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { MasterToggleCard(state = state, onToggle = { viewModel.setMasterEnabled(!state.masterEnable) }, onResetDefaults = onResetDefaults) }
        item { InteractiveWrapper(enabled = interactive) { MasterLimiterRows(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { PlaybackGainSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { LUFSTargetingSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { MultibandCompressorSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { FetCompressorSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { DdcSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { SpectrumExtensionSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { EqualizerSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { DynamicEqSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { ConvolverSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { FieldSurroundSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { DiffSurroundSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { StereoImagerSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { HeadphoneSurroundSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { ReverberationSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { DynamicSystemSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { TubeSimulatorSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { PsychoacousticBassSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { ViperBassSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { ViperBassMonoSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { ViperClaritySection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { AuditoryProtectionSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { AnalogXSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { SpeakerOptSection(state, viewModel) } }
        item { InteractiveWrapper(enabled = interactive) { CopyrightSection() } }
    }
}

@Composable
private fun InteractiveWrapper(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalInteractionEnabled provides enabled) { content() }
}