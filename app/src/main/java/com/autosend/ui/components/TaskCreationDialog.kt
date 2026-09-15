package com.autosend.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.models.ScheduledTask
import com.autosend.ui.theme.AutoSendColors
import com.autosend.utils.CronUtils
import com.autosend.utils.LunarCalendarUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TITLE_CREATE = "创建定时任务"
private const val TITLE_EDIT = "编辑定时任务"
private const val LABEL_FEISHU_TARGET = "发送到"
private const val VALUE_UNSELECTED = "未选择"
private const val MODE_ONCE = "单次定时"
private const val MODE_CRON = "重复任务"
private const val MODE_LUNAR = "阴历"
private const val LABEL_CRON_CONFIG = "重复规则"
private const val LABEL_CRON_RAW = "Cron 表达式"
private const val LABEL_CRON_PREVIEW = "触发时间预览"
private const val LABEL_CRON_HINT = "支持 L：放在“日”位表示当月最后一天，例如 0 0 20 L * *"
private const val LABEL_MESSAGE_TIME = "发送时间（yyyy-MM-dd HH:mm）"
private const val LABEL_MESSAGE_CONTENT = "消息内容"
private const val LABEL_LUNAR_MONTH = "月份（1-12）"
private const val LABEL_LUNAR_DAY = "日期（1-30）"
private const val LABEL_LUNAR_TIME = "发送时间（HH:mm）"
private const val LABEL_LUNAR_TIME_HINT = "默认 00:00，可按需修改"
private const val LABEL_LUNAR_LEAP = "闰月"
private const val LABEL_LUNAR_LEAP_HINT = "开启后仅在闰月触发，普通生日请保持关闭"
private const val LABEL_LUNAR_HINT = "设置阴历月日后，每年当天 00:00 自动发送；如果该年没有对应的闰月日期，将顺延到下一次可用日期。"
private const val ACTION_CANCEL = "取消"
private const val ACTION_CREATE = "立即创建"
private const val ACTION_SAVE = "保存修改"

private enum class ScheduleMode {
    ONCE,
    CRON,
    LUNAR
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TaskCreationDialog(
    feishuWebhooks: List<FeishuWebhook>,
    editingTask: ScheduledTask? = null,
    initialFeishuWebhookId: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        feishuWebhookId: Long,
        content: String,
        time: Long,
        cron: String?,
        lunarMonth: Int?,
        lunarDay: Int?,
        lunarLeapMonth: Boolean
    ) -> Unit
) {
    val dialogScrollState = rememberScrollState()
    val messageBringIntoViewRequester = remember { BringIntoViewRequester() }
    var messageInputFocused by remember { mutableStateOf(false) }

    LaunchedEffect(messageInputFocused) {
        if (messageInputFocused) {
            delay(150)
            messageBringIntoViewRequester.bringIntoView()
        }
    }

    var selectedWebhook by remember(editingTask, initialFeishuWebhookId, feishuWebhooks) {
        mutableStateOf(
            feishuWebhooks.find {
                it.id == (editingTask?.feishuWebhookId ?: initialFeishuWebhookId)
            } ?: feishuWebhooks.firstOrNull()
        )
    }
    var content by remember(editingTask) { mutableStateOf(editingTask?.content.orEmpty()) }
    var scheduleMode by remember(editingTask) {
        mutableStateOf(
            when {
                editingTask?.isLunarRecurring == true -> ScheduleMode.LUNAR
                editingTask?.cronExpression?.isNotBlank() == true -> ScheduleMode.CRON
                else -> ScheduleMode.ONCE
            }
        )
    }
    var cronParts by remember(editingTask) {
        mutableStateOf(
            editingTask?.cronExpression?.split(" ")?.toMutableList()
                ?: mutableListOf("0", "0", "*", "*", "*", "*")
        )
    }
    var selectedPartIndex by remember(editingTask) { mutableIntStateOf(1) }
    val initialTime = editingTask?.let { Date(it.scheduledTime) }
        ?: Date(System.currentTimeMillis() + 600000)
    val defaultLunarDate = remember(editingTask) {
        editingTask?.let { task ->
            task.lunarMonth?.let { month ->
                task.lunarDay?.let { day ->
                    com.autosend.utils.LunarDate(month, day, task.lunarLeapMonth)
                }
            }
        } ?: LunarCalendarUtils.getLunarDate(initialTime.time)
    }
    var lunarMonthString by remember(editingTask) {
        mutableStateOf(editingTask?.lunarMonth?.toString() ?: defaultLunarDate.month.toString())
    }
    var lunarDayString by remember(editingTask) {
        mutableStateOf(editingTask?.lunarDay?.toString() ?: defaultLunarDate.day.toString())
    }
    var lunarLeapMonth by remember(editingTask) {
        mutableStateOf(editingTask?.lunarLeapMonth ?: false)
    }
    var lunarTimeString by remember(editingTask) {
        mutableStateOf(
            editingTask?.let {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.scheduledTime))
            } ?: "00:00"
        )
    }
    var timeString by remember(editingTask) {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(initialTime))
    }

    val cronExpression = cronParts.joinToString(" ")
    val parsedTime = remember(timeString) { parseScheduleTime(timeString) }
    val isCronMode = scheduleMode == ScheduleMode.CRON
    val nextExecutionTimes = remember(cronExpression, scheduleMode) {
        if (isCronMode) CronUtils.getNextExecutionTimes(cronExpression) else emptyList()
    }
    val lunarMonth = lunarMonthString.toIntOrNull()
    val lunarDay = lunarDayString.toIntOrNull()
    val parsedLunarTime = remember(lunarTimeString) { parseLunarTime(lunarTimeString) }
    val lunarNextExecutionTimes = remember(
        lunarMonthString,
        lunarDayString,
        lunarLeapMonth,
        lunarTimeString,
        scheduleMode
    ) {
        if (
            scheduleMode == ScheduleMode.LUNAR &&
            lunarMonth != null &&
            lunarDay != null &&
            parsedLunarTime != null
        ) {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = parsedLunarTime.time
            }
            LunarCalendarUtils.getNextExecutionTimes(
                month = lunarMonth,
                day = lunarDay,
                hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
                minute = calendar.get(java.util.Calendar.MINUTE),
                leapMonth = lunarLeapMonth,
                count = 5
            )
        } else {
            emptyList()
        }
    }
    val lunarValidationMessage = when {
        scheduleMode != ScheduleMode.LUNAR -> null
        !LunarCalendarUtils.isValidDate(lunarMonth, lunarDay) -> "请输入有效的阴历月份（1-12）和日期（1-30）"
        parsedLunarTime == null -> "请输入正确的时间格式（HH:mm）"
        lunarNextExecutionTimes.isEmpty() -> "未来几年内找不到对应日期，请检查月份、日期或闰月设置"
        else -> null
    }
    val canConfirm = selectedWebhook != null && content.isNotBlank() && when (scheduleMode) {
        ScheduleMode.ONCE -> parsedTime != null
        ScheduleMode.CRON -> nextExecutionTimes.isNotEmpty()
        ScheduleMode.LUNAR -> lunarValidationMessage == null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.98f)
                    .fillMaxHeight(0.94f),
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, AutoSendColors.line)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = if (editingTask == null) TITLE_CREATE else TITLE_EDIT,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = AutoSendColors.ink
                    )
                    Spacer(Modifier.height(16.dp))

                    Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(dialogScrollState)
                    ) {
                    Text(
                        LABEL_FEISHU_TARGET,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    var showWebhookMenu by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth()) {
                        TargetCard(
                            label = "飞书 Webhook",
                            value = selectedWebhook?.name ?: VALUE_UNSELECTED,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showWebhookMenu = true }
                        )
                        DropdownMenu(
                            expanded = showWebhookMenu,
                            onDismissRequest = { showWebhookMenu = false }
                        ) {
                            feishuWebhooks.forEach { webhook ->
                                DropdownMenuItem(
                                    text = { Text(webhook.name) },
                                    onClick = {
                                        selectedWebhook = webhook
                                        showWebhookMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (feishuWebhooks.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请先在配置管理的“飞书”页添加 Webhook",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = AutoSendColors.blueTint.copy(alpha = 0.55f),
                        tonalElevation = 0.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            listOf(
                                ScheduleMode.ONCE to MODE_ONCE,
                                ScheduleMode.CRON to MODE_CRON,
                                ScheduleMode.LUNAR to MODE_LUNAR
                            ).forEach { (mode, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { scheduleMode = mode }
                                        .padding(vertical = 8.dp, horizontal = 2.dp)
                                ) {
                                    RadioButton(
                                        selected = scheduleMode == mode,
                                        onClick = { scheduleMode = mode }
                                    )
                                    Text(label, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }

                    if (isCronMode) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            LABEL_CRON_CONFIG,
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        val labels = listOf("秒", "分", "时", "日", "月", "周")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                                    androidx.compose.material3.MaterialTheme.shapes.small
                                )
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            labels.forEachIndexed { index, label ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(androidx.compose.material3.MaterialTheme.shapes.small)
                                        .clickable { selectedPartIndex = index }
                                        .background(
                                            if (selectedPartIndex == index) {
                                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        cronParts[index],
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedPartIndex == index) {
                                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        label,
                                        fontSize = 10.sp,
                                        color = if (selectedPartIndex == index) {
                                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        } else {
                                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "快捷设置（${labels[selectedPartIndex]}）",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                        FlowRow(Modifier.fillMaxWidth()) {
                            val options = when (selectedPartIndex) {
                                0, 1 -> listOf("*", "0", "*/5", "*/10", "*/30")
                                2 -> listOf("*", "0", "9", "12", "18", "22")
                                5 -> listOf("*", "1", "2", "3", "4", "5", "6", "0")
                                else -> listOf("*", "1")
                            }
                            options.forEach { option ->
                                AssistChip(
                                    onClick = {
                                        cronParts = cronParts.toMutableList().also {
                                            it[selectedPartIndex] = option
                                        }
                                    },
                                    label = { Text(option) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "常用预设",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                        FlowRow(Modifier.fillMaxWidth()) {
                            listOf(
                                "每 5 分钟" to "0 */5 * * * *",
                                "每 10 分钟" to "0 */10 * * * *",
                                "每小时" to "0 0 * * * *",
                                "每天 9 点" to "0 0 9 * * *",
                                "每天 12 点" to "0 0 12 * * *",
                                "每天 20 点" to "0 0 20 * * *",
                                "工作日 9 点" to "0 0 9 * * 1-5",
                                "每月最后一天 20 点" to "0 0 20 L * *"
                            ).forEach { (label, value) ->
                                AssistChip(
                                    onClick = { cronParts = value.split(" ").toMutableList() },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cronExpression,
                            onValueChange = { value ->
                                val parts = value.trim().split(Regex("\\s+"))
                                if (parts.size == 6) cronParts = parts.toMutableList()
                            },
                            label = { Text(LABEL_CRON_RAW) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            LABEL_CRON_HINT,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.35f
                                )
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    LABEL_CRON_PREVIEW,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                val displayFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                                nextExecutionTimes.take(5).forEach { time ->
                                    Text(
                                        displayFormat.format(Date(time)),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else if (scheduleMode == ScheduleMode.LUNAR) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "阴历生日设置",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = lunarMonthString,
                                onValueChange = { lunarMonthString = it.filter { char -> char.isDigit() }.take(2) },
                                label = { Text(LABEL_LUNAR_MONTH) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = lunarMonth == null || lunarMonth !in 1..12,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = lunarDayString,
                                onValueChange = { lunarDayString = it.filter { char -> char.isDigit() }.take(2) },
                                label = { Text(LABEL_LUNAR_DAY) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = lunarDay == null || lunarDay !in 1..30,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = lunarLeapMonth,
                                onCheckedChange = { lunarLeapMonth = it }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(LABEL_LUNAR_LEAP, fontWeight = FontWeight.Medium)
                                Text(
                                    LABEL_LUNAR_LEAP_HINT,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lunarTimeString,
                            onValueChange = { value ->
                                lunarTimeString = value
                                    .filter { char -> char.isDigit() || char == ':' }
                                    .take(5)
                            },
                            label = { Text(LABEL_LUNAR_TIME) },
                            supportingText = { Text(LABEL_LUNAR_TIME_HINT) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = parsedLunarTime == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            LABEL_LUNAR_HINT,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        lunarValidationMessage?.let { message ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                message,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error
                            )
                        }
                        lunarNextExecutionTimes.firstOrNull()?.let { nextTime ->
                            Spacer(Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.35f
                                    )
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "下次触发：${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(nextTime))}",
                                    modifier = Modifier.padding(12.dp),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = timeString,
                            onValueChange = { timeString = it },
                            label = { Text(LABEL_MESSAGE_TIME) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(LABEL_MESSAGE_CONTENT) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(messageBringIntoViewRequester)
                            .onFocusChanged { messageInputFocused = it.isFocused },
                        minLines = 3
                    )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text(ACTION_CANCEL) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val time = when (scheduleMode) {
                                    ScheduleMode.CRON -> nextExecutionTimes.firstOrNull()
                                    ScheduleMode.LUNAR -> lunarNextExecutionTimes.firstOrNull()
                                    ScheduleMode.ONCE -> parsedTime?.time
                                }
                                if (selectedWebhook != null && time != null && content.isNotBlank() && canConfirm) {
                                    onConfirm(
                                        selectedWebhook!!.id,
                                        content,
                                        time,
                                        cronExpression.takeIf { scheduleMode == ScheduleMode.CRON },
                                        lunarMonth.takeIf { scheduleMode == ScheduleMode.LUNAR },
                                        lunarDay.takeIf { scheduleMode == ScheduleMode.LUNAR },
                                        lunarLeapMonth && scheduleMode == ScheduleMode.LUNAR
                                    )
                                }
                            },
                            enabled = canConfirm
                        ) {
                            Text(if (editingTask == null) ACTION_CREATE else ACTION_SAVE)
                        }
                    }
                }
            }
        }
    }
}

private fun parseScheduleTime(value: String): Date? {
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            isLenient = false
        }.parse(value)
    }.getOrNull()
}

private fun parseLunarTime(value: String): Date? {
    return runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            isLenient = false
        }.parse(value)
    }.getOrNull()
}

@Composable
private fun TargetCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = AutoSendColors.blueTint.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AutoSendColors.line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = AutoSendColors.blue
                )
                Text(
                    value,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择目标",
                tint = AutoSendColors.blue
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        content = { content() }
    )
}
