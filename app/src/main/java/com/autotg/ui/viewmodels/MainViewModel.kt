package com.autotg.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotg.data.models.DeliveryChannel
import com.autotg.data.models.LogStatus
import com.autotg.data.models.MessageParseMode
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.TaskStatus
import com.autotg.data.repository.TelegramRepository
import com.autotg.utils.ExitReasonTracker
import com.autotg.utils.RecurringScheduleUtils
import com.autotg.utils.SchedulerRecovery
import com.autotg.utils.WorkManagerHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
            SchedulerRecovery.recoverEnabledTasks(context, repository)
        }
    }

    val allTasks: StateFlow<List<ScheduledTask>> = repository.getAllTasks()
        .map { tasks -> tasks.filter { it.deliveryChannel == DeliveryChannel.FEISHU } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feishuWebhooks = repository.getAllFeishuWebhooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<TaskLog>> = repository.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        viewModelScope.launch { repository.clearAllLogs() }
    }

    fun createTask(
        feishuWebhookId: Long,
        content: String,
        scheduledTime: Long,
        cronExpression: String? = null,
        lunarMonth: Int? = null,
        lunarDay: Int? = null,
        lunarLeapMonth: Boolean = false
    ) {
        viewModelScope.launch {
            val draftTask = ScheduledTask(
                deliveryChannel = DeliveryChannel.FEISHU,
                feishuWebhookId = feishuWebhookId,
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
                deliveryChannel = DeliveryChannel.FEISHU,
                botId = null,
                chatId = null,
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
