package com.autosend.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autosend.ui.theme.AutoSendColors
import com.autosend.utils.LunarCalendarUtils
import com.autosend.utils.LunarDate

/** 年度阴历提醒只选择月日；三十在小月年份跳过，与调度规则保持一致。 */
@Composable
fun LunarDatePickerDialog(
    initialDate: LunarDate,
    onDismiss: () -> Unit,
    onConfirm: (LunarDate) -> Unit
) {
    var month by remember { mutableIntStateOf(initialDate.month) }
    var day by remember { mutableIntStateOf(initialDate.day) }
    var showMonths by remember { mutableStateOf(false) }
    val selectedDate = LunarDate(month, day, initialDate.isLeapMonth)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text("选择阴历日期", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(LunarCalendarUtils.formatDate(selectedDate), color = AutoSendColors.blue,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { month = if (month == 1) 12 else month - 1 }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "上一个阴历月")
                    }
                    Column {
                        TextButton(onClick = { showMonths = true }) {
                            Text(LunarCalendarUtils.monthName(month), fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "选择阴历月")
                        }
                        DropdownMenu(expanded = showMonths, onDismissRequest = { showMonths = false }) {
                            (1..12).forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(LunarCalendarUtils.monthName(value)) },
                                    onClick = { month = value; showMonths = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { month = if (month == 12) 1 else month + 1 }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "下一个阴历月")
                    }
                }
                Spacer(Modifier.height(8.dp))
                (1..30).chunked(5).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { value ->
                            val isSelected = day == value
                            Surface(
                                onClick = { day = value },
                                modifier = Modifier.weight(1f).semantics {
                                    selected = isSelected
                                    role = Role.RadioButton
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) AutoSendColors.blue else AutoSendColors.blueTint.copy(alpha = 0.4f),
                                contentColor = if (isSelected) androidx.compose.ui.graphics.Color.White else AutoSendColors.ink,
                                border = if (isSelected) BorderStroke(1.dp, AutoSendColors.blue) else null
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.height(48.dp), contentAlignment = Alignment.Center
                                ) {
                                    Text(LunarCalendarUtils.dayName(value), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("按阴历月日每年重复；遇到小月没有三十时，等待下一次有效日期。",
                    style = MaterialTheme.typography.bodySmall, color = AutoSendColors.muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = { onConfirm(selectedDate) }) { Text("确定") } }
    )
}
