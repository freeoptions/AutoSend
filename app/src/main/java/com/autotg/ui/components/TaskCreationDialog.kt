package com.autotg.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.MessageParseMode
import com.autotg.data.models.ScheduledTask
import com.autotg.utils.CronUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TITLE_CREATE = "\u521b\u5efa\u5b9a\u65f6\u4efb\u52a1"
private const val TITLE_EDIT = "\u7f16\u8f91\u5b9a\u65f6\u4efb\u52a1"
private const val LABEL_BOT = "\u673a\u5668\u4eba"
private const val LABEL_CHAT = "\u7fa4\u7ec4"
private const val VALUE_UNSELECTED = "\u672a\u9009\u62e9"
private const val MODE_ONCE = "\u5355\u6b21\u5b9a\u65f6"
private const val MODE_CRON = "Cron \u8868\u8fbe\u5f0f"
private const val LABEL_CRON_CONFIG = "Cron \u914d\u7f6e"
private const val LABEL_CRON_RAW = "Cron \u539f\u59cb\u8868\u8fbe\u5f0f"
private const val LABEL_CRON_PREVIEW = "\u89e6\u53d1\u65f6\u95f4\u9884\u89c8"
private const val LABEL_CRON_HINT = "支持 L：放在“日”位时表示当月最后一天，例如 0 0 20 L * *。"
private const val LABEL_MESSAGE_TIME = "\u53d1\u9001\u65f6\u95f4 (yyyy-MM-dd HH:mm)"
private const val LABEL_MESSAGE_CONTENT = "\u6d88\u606f\u5185\u5bb9"
private const val LABEL_PARSE_MODE = "\u53d1\u9001\u683c\u5f0f"
private const val MODE_PLAIN = "\u666e\u901a\u6587\u672c"
private const val MODE_MARKDOWN_V2 = "MarkdownV2"
private const val MARKDOWN_HINT = "\u542f\u7528\u540e\u4f1a\u6309 Telegram MarkdownV2 \u53d1\u9001\uff0c\u7279\u6b8a\u5b57\u7b26\u8bf7\u6309 Telegram \u89c4\u5219\u8f6c\u4e49\u3002"
private const val ACTION_CANCEL = "\u53d6\u6d88"
private const val ACTION_CREATE = "\u7acb\u5373\u521b\u5efa"
private const val ACTION_SAVE = "\u4fdd\u5b58\u4fee\u6539"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreationDialog(
    bots: List<Bot>,
    chats: List<Chat>,
    editingTask: ScheduledTask? = null,
    initialBotId: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        botId: Long,
        chatId: Long,
        content: String,
        parseMode: MessageParseMode,
        time: Long,
        cron: String?
    ) -> Unit
) {
    var selectedBot by remember {
        mutableStateOf(
            bots.find { it.id == (editingTask?.botId ?: initialBotId) } ?: bots.firstOrNull()
        )
    }
    var selectedChat by remember {
        mutableStateOf(chats.find { it.id == editingTask?.chatId } ?: chats.firstOrNull())
    }
    var content by remember { mutableStateOf(editingTask?.content ?: "") }
    var parseMode by remember { mutableStateOf(editingTask?.parseMode ?: MessageParseMode.NONE) }

    var isCronMode by remember { mutableStateOf(editingTask?.cronExpression != null) }
    var cronParts by remember {
        val initial = editingTask?.cronExpression?.split(" ")?.toMutableList()
            ?: mutableListOf("0", "0", "*", "*", "*", "*")
        mutableStateOf(initial)
    }
    var selectedPartIndex by remember { mutableIntStateOf(1) }

    var timeString by remember {
        val initialTime = if (editingTask != null) {
            Date(editingTask.scheduledTime)
        } else {
            Date(System.currentTimeMillis() + 600000)
        }
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(initialTime))
    }

    val cronExpression = cronParts.joinToString(" ")
    val nextExecutionTimes = remember(cronExpression, isCronMode, timeString) {
        if (isCronMode) CronUtils.getNextExecutionTimes(cronExpression) else emptyList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (editingTask == null) TITLE_CREATE else TITLE_EDIT,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        var showBotMenu by remember { mutableStateOf(false) }
                        var showChatMenu by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.weight(1f)) {
                            InfoCard(
                                label = LABEL_BOT,
                                value = selectedBot?.name ?: VALUE_UNSELECTED,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showBotMenu = true }
                            )
                            DropdownMenu(expanded = showBotMenu, onDismissRequest = { showBotMenu = false }) {
                                bots.forEach { bot ->
                                    DropdownMenuItem(
                                        text = { Text(bot.name) },
                                        onClick = {
                                            selectedBot = bot
                                            showBotMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            InfoCard(
                                label = LABEL_CHAT,
                                value = selectedChat?.name ?: VALUE_UNSELECTED,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showChatMenu = true }
                            )
                            DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                                chats.forEach { chat ->
                                    DropdownMenuItem(
                                        text = { Text(chat.name) },
                                        onClick = {
                                            selectedChat = chat
                                            showChatMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { isCronMode = false }
                                    .padding(8.dp)
                            ) {
                                RadioButton(selected = !isCronMode, onClick = { isCronMode = false })
                                Text(MODE_ONCE, style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { isCronMode = true }
                                    .padding(8.dp)
                            ) {
                                RadioButton(selected = isCronMode, onClick = { isCronMode = true })
                                Text(MODE_CRON, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (isCronMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(LABEL_CRON_CONFIG, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        val labels = listOf("\u79d2", "\u5206", "\u65f6", "\u65e5", "\u6708", "\u5468")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            labels.forEachIndexed { index, label ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { selectedPartIndex = index }
                                        .background(
                                            if (selectedPartIndex == index) MaterialTheme.colorScheme.primary else Color.Transparent
                                        )
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = cronParts[index],
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedPartIndex == index) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        color = if (selectedPartIndex == index) {
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "\u5feb\u6377\u8bbe\u5b9a (${labels[selectedPartIndex]})",
                            style = MaterialTheme.typography.labelMedium
                        )
                        FlowRow(modifier = Modifier.fillMaxWidth()) {
                            val options = when (selectedPartIndex) {
                                0, 1 -> listOf("*", "0", "*/5", "*/10", "*/30")
                                2 -> listOf("*", "0", "9", "12", "18", "22")
                                5 -> listOf("*", "1", "2", "3", "4", "5", "6", "0")
                                else -> listOf("*", "1")
                            }
                            options.forEach { option ->
                                AssistChip(
                                    onClick = {
                                        val newParts = cronParts.toMutableList()
                                        newParts[selectedPartIndex] = option
                                        cronParts = newParts
                                    },
                                    label = { Text(option) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("\u5e38\u7528\u9884\u8bbe", style = MaterialTheme.typography.labelMedium)
                        FlowRow(modifier = Modifier.fillMaxWidth()) {
                            val presets = listOf(
                                "\u6bcf 5 \u5206\u949f" to "0 */5 * * * *",
                                "\u6bcf 10 \u5206\u949f" to "0 */10 * * * *",
                                "\u6bcf\u5c0f\u65f6" to "0 0 * * * *",
                                "\u6bcf\u5929 9 \u70b9" to "0 0 9 * * *",
                                "\u6bcf\u5929 12 \u70b9" to "0 0 12 * * *",
                                "\u6bcf\u5929 20 \u70b9" to "0 0 20 * * *",
                                "\u5de5\u4f5c\u65e5 9 \u70b9" to "0 0 9 * * 1-5",
                                "\u6bcf\u6708\u6700\u540e\u4e00\u5929 20 \u70b9" to "0 0 20 L * *"
                            )
                            presets.forEach { (label, value) ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        val parts = value.split(" ")
                                        if (parts.size == 6) cronParts = parts.toMutableList()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cronExpression,
                            onValueChange = {
                                val parts = it.split(" ")
                                if (parts.size == 6) cronParts = parts.toMutableList()
                            },
                            label = { Text(LABEL_CRON_RAW) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = LABEL_CRON_HINT,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    LABEL_CRON_PREVIEW,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Red,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
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
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = timeString,
                            onValueChange = { timeString = it },
                            label = { Text(LABEL_MESSAGE_TIME) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(LABEL_MESSAGE_CONTENT) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(LABEL_PARSE_MODE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = parseMode == MessageParseMode.NONE,
                            onClick = { parseMode = MessageParseMode.NONE },
                            label = { Text(MODE_PLAIN) }
                        )
                        FilterChip(
                            selected = parseMode == MessageParseMode.MARKDOWN_V2,
                            onClick = { parseMode = MessageParseMode.MARKDOWN_V2 },
                            label = { Text(MODE_MARKDOWN_V2) }
                        )
                    }
                    if (parseMode == MessageParseMode.MARKDOWN_V2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = MARKDOWN_HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(ACTION_CANCEL) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val botId = selectedBot?.id
                            val chatId = selectedChat?.id
                            val time = if (isCronMode) {
                                nextExecutionTimes.firstOrNull() ?: System.currentTimeMillis()
                            } else {
                                try {
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .parse(timeString)
                                        ?.time
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            if (botId != null && chatId != null && time != null && content.isNotBlank()) {
                                onConfirm(
                                    botId,
                                    chatId,
                                    content,
                                    parseMode,
                                    time,
                                    if (isCronMode) cronExpression else null
                                )
                            }
                        }
                    ) {
                        Text(if (editingTask == null) ACTION_CREATE else ACTION_SAVE)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
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
