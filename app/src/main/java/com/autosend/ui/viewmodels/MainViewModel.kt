package com.autosend.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autosend.data.models.DeliveryChannel
import com.autosend.data.models.DeliveryTarget
import com.autosend.data.models.LogStatus
import com.autosend.data.models.MessageParseMode
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskLog
import com.autosend.data.models.TaskStatus
import com.autosend.data.repository.TelegramRepository
import com.autosend.utils.ExitReasonTracker
import com.autosend.utils.DeliveryChannelPolicy
import com.autosend.utils.RecurringScheduleUtils
import com.autosend.utils.SchedulerRecovery
import com.autosend.utils.WorkManagerHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: TelegramRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    init {
        viewModelScope.launch {
            ExitReasonTracker.recordLatestExitIfNeeded(context, repository)
            repository.migrateFeishuTasksToSingleQqBot()
            SchedulerRecovery.recoverEnabledTasks(context, repository)
        }
    }

    val allTasks: StateFlow<List<ScheduledTask>> = repository.getAllTasks()
        .map { tasks -> tasks.filter { DeliveryChannelPolicy.isEnabled(it.deliveryChannel) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val qqBots = repository.getAllQqBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deliveryTargets: StateFlow<List<DeliveryTarget>> = qqBots
        .map { bots -> bots.map { DeliveryTarget(DeliveryChannel.QQ, it.id, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<TaskLog>> = combine(repository.getAllLogs(), allTasks) { logs, tasks ->
        val visibleTaskIds = tasks.mapTo(HashSet<Long>()) { it.id }
        logs.filter { it.taskId in visibleTaskIds }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        viewModelScope.launch { repository.clearVisibleLogs() }
    }

    fun markLogsAsRead() {
        viewModelScope.launch { repository.markAllVisibleLogsAsRead() }
    }

    fun createTask(
        target: DeliveryTarget,
        content: String,
        scheduledTime: Long,
        cronExpression: String? = null,
        lunarMonth: Int? = null,
        lunarDay: Int? = null,
        lunarLeapMonth: Boolean = false
    ) {
        viewModelScope.launch {
            val draftTask = ScheduledTask(
                deliveryChannel = target.channel,
                feishuWebhookId = target.id.takeIf { target.channel == DeliveryChannel.FEISHU },
                qqBotId = target.id.takeIf { target.channel == DeliveryChannel.QQ },
                content = content,
                parseMode = MessageParseMode.NONE,
                scheduledTime = scheduledTime,
                cronExpression = cronExpression,
                lunarMonth = lunarMonth,
                lunarDay = lunarDay,
                lunarLeapMonth = lunarLeapMonth
            )
            val task = draftTask.copy(scheduledTime = normalizeScheduledTime(draftTask))
            val id = repository.insertTask(task)
            WorkManagerHelper.scheduleTask(context, task.copy(id = id))
        }
    }

    fun deleteTask(task: ScheduledTask) {
        viewModelScope.launch {
            repository.deleteTask(task)
            WorkManagerHelper.cancelTask(context, task.id)
        }
    }

    fun toggleTaskEnabled(task: ScheduledTask, enabled: Boolean) {
        viewModelScope.launch {
            var updatedTask = task.copy(isEnabled = enabled)
            if (enabled) {
                val now = System.currentTimeMillis()
                if (updatedTask.scheduledTime <= now) {
                    RecurringScheduleUtils.getNextExecutionTimeFrom(updatedTask, now + 1_000L)
                        ?.let { nextTime ->
                            updatedTask = updatedTask.copy(scheduledTime = nextTime, status = TaskStatus.PENDING)
                    }
                }
                repository.updateTask(updatedTask)
                if (updatedTask.scheduledTime > now) {
                    WorkManagerHelper.scheduleTask(context, updatedTask)
                }
            } else {
                repository.updateTask(updatedTask)
                WorkManagerHelper.cancelTask(context, updatedTask.id)
            }
        }
    }

    fun updateTask(task: ScheduledTask) {
        viewModelScope.launch {
            val updatedTask = task.copy(
                botId = task.botId.takeIf { task.deliveryChannel == DeliveryChannel.TELEGRAM },
                chatId = task.chatId.takeIf { task.deliveryChannel == DeliveryChannel.TELEGRAM },
                feishuWebhookId = task.feishuWebhookId.takeIf { task.deliveryChannel == DeliveryChannel.FEISHU },
                qqBotId = task.qqBotId.takeIf { task.deliveryChannel == DeliveryChannel.QQ },
                parseMode = MessageParseMode.NONE,
                scheduledTime = normalizeScheduledTime(task)
            )
            repository.updateTask(updatedTask)
            if (updatedTask.isEnabled) {
                WorkManagerHelper.scheduleTask(context, updatedTask)
            } else {
                WorkManagerHelper.cancelTask(context, updatedTask.id)
            }
        }
    }

    private fun normalizeScheduledTime(task: ScheduledTask): Long {
        if (!RecurringScheduleUtils.isRecurring(task)) return task.scheduledTime
        if (task.scheduledTime > System.currentTimeMillis()) return task.scheduledTime
        return RecurringScheduleUtils.getNextExecutionTimeFrom(task) ?: task.scheduledTime
    }

    fun sendTaskNow(task: ScheduledTask) {
        viewModelScope.launch {
            if (!DeliveryChannelPolicy.isEnabled(task.deliveryChannel)) return@launch
            val result = repository.sendScheduledMessage(task.id, isManual = true)
            val target = repository.describeTaskTarget(task)
            val latestTask = repository.getTaskById(task.id) ?: task
            repository.updateTask(
                latestTask.copy(
                    status = if (result.isSuccess) TaskStatus.SUCCESS else TaskStatus.FAILED,
                    lastError = result.exceptionOrNull()?.message,
                    retryCount = if (result.isSuccess) 0 else latestTask.retryCount + 1
                )
            )
            repository.insertLog(
                TaskLog(
                    taskId = task.id,
                    taskContent = "[手动执行] ${task.content}",
                    botName = target.channelName,
                    chatName = target.targetName,
                    status = if (result.isSuccess) LogStatus.SUCCESS else LogStatus.FAILED,
                    errorMessage = result.exceptionOrNull()?.message
                )
            )
        }
    }

    fun retryFailedLog(log: TaskLog) {
        viewModelScope.launch {
            repository.getTaskById(log.taskId)?.let { sendTaskNow(it) }
        }
    }
}
