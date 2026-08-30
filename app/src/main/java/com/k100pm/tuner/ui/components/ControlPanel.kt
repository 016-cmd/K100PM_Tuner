package com.k100pm.tuner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k100pm.tuner.service.*
import com.k100pm.tuner.ui.MainViewModel

/**
 * WSA 控件面板
 * 
 * 按类别分组展示所有寄存器控件.
 * 模块未安装时所有控件置灰不可操作.
 */
@Composable
fun ControlPanel(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val controlValues by viewModel.controlValues.collectAsState()
    val enabled = viewModel.canEdit

    Column(modifier = modifier) {
        // 分类 Tab
        ControlCategory.entries.sortedBy { it.order }.forEach { category ->
            val controls = ControlRegistry.grouped[category] ?: return@forEach

            CategorySection(
                title = category.displayName,
                enabled = enabled,
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                controls.forEach { control ->
                    val currentValue = controlValues[control.tinymixId] ?: "—"
                    ControlItem(
                        control = control,
                        currentValue = currentValue,
                        enabled = enabled,
                        onValueChange = { newValue ->
                            viewModel.setControl(control, newValue)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 分类区块
 */
@Composable
private fun CategorySection(
    title: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column {
            // 分类标题
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 单个控件条目
 */
@Composable
private fun ControlItem(
    control: WsaControl,
    currentValue: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 芯片 + 通道标签
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${control.label}${
                    if (control.channel != "-") " · ${control.channel}" else ""
                }",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(control.chip.displayName)
                    if (control.description.isNotBlank()) {
                        append(" · ")
                        append(control.description)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        // 控件本体
        when (control.controlType) {
            ControlType.BOOL -> {
                Switch(
                    checked = currentValue.equals("On", ignoreCase = true),
                    onCheckedChange = { checked ->
                        onValueChange(if (checked) "1" else "0")
                    },
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            ControlType.ENUM -> {
                var expanded by remember { mutableStateOf(false) }

                Box {
                    GlassSurface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentValue.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        control.enumValues.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(option, fontWeight = if (option == currentValue) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                                },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            else -> { /* INT 类型已移除 */ }
        }
    }
}