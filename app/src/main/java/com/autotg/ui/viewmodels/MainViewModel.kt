package com.autotg.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotg.data.models.*
import com.autotg.data.repository.TelegramRepository
import com.autotg.utils.ExitReasonTracker
import com.autotg.utils.SchedulerRecovery
import com.autotg.utils.WorkManagerHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.autotg.utils.CronUtils

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

    private val _botFilter = MutableStateFlow<Long?>(null)
    val botFilter = _botFilter.asStateFlow()

    private val _chatFilter = MutableStateFlow<Long?>(null)
    val chatFilter = _chatFilter.asStateFlow()

    val allTasks: StateFlow<List<ScheduledTask>> = repository.getAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tasks: StateFlow<List<ScheduledTask>> = combine(
        repository.getAllTasks(),
        _botFilter,
        _chatFilter
    ) { tasks, botId, chatId ->
        tasks.filter { task ->
            (botId == null || task.botId == botId) &&
            (chatId == null || task.chatId == chatId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bots: StateFlow<List<Bot>> = repository.getAllBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chats: StateFlow<List<Chat>> = repository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<TaskLog>> = repository.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBotFilter(botId: Long?) {
        _botFilter.value = botId
    }

    fun setChatFilter(chatId: Long?) {
        _chatFilter.value = chatId
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearAllLogs()
        }
    }

    fun createTask(
        botId: Long,
        chatId: Long,
        content: String,
        parseMode: MessageParseMode,
        scheduledTime: Long,
        cronExpression: String? = null
    ) {
        viewModelScope.launch {
            val normalizedTime = normalizeScheduledTime(scheduledTime, cronExpression)
            val task = ScheduledTask(
                botId = botId,
                chatId = chatId,
                content = content,
                parseMode = parseMode,
                scheduledTime = normalizedTime,
                cronExpression = cronExpression
            )
            val id = repository.insertTask(task)
            val savedTask = task.copy(id = id)
            WorkManagerHelper.scheduleTask(context, savedTask)
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
                // 如果是启用操作，且当前设定的时间已经过期
                if (updatedTask.scheduledTime <= now) {
                    val cron = updatedTask.cronExpression
                    if (cron != null) {
                        // 如果是 Cron 任务，重新计算未来的时间，不要用过去的时间去触发
                        val nextTimes = CronUtils.getNextExecutionTimes(cron, 1)
                        if (nextTimes.isNotEmpty()) {
                            updatedTask = updatedTask.copy(scheduledTime = nextTimes[0], status = TaskStatus.PENDING)
                        }
                    }
                }

                repository.updateTask(updatedTask)
                // 只有在时间是未来的情况下才去调度
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
                scheduledTime = normalizeScheduledTime(task.scheduledTime, task.cronExpression)
            )
            repository.updateTask(updatedTask)
            if (updatedTask.isEnabled) {
                WorkManagerHelper.scheduleTask(context, updatedTask)
            } else {
                WorkManagerHelper.cancelTask(context, updatedTask.id)
            }
        }
    }

    private fun normalizeScheduledTime(scheduledTime: Long, cronExpression: String?): Long {
        val cron = cronExpression?.takeIf { it.isNotBlank() } ?: return scheduledTime
        if (scheduledTime > System.currentTimeMillis()) return scheduledTime
        return CronUtils.getNextExecutionTimes(cron, 1).firstOrNull() ?: scheduledTime
    }

    fun sendTaskNow(task: ScheduledTask) {
        viewModelScope.launch {
            val result = repository.sendScheduledMessage(task.id, isManual = true)
            val bot = bots.value.find { it.id == task.botId }
            val chat = chats.value.find { it.id == task.chatId }
            val latestTask = repository.getTaskById(task.id) ?: task

            repository.updateTask(
                latestTask.copy(
                    status = if (result.isSuccess) TaskStatus.SUCCESS else TaskStatus.FAILED,
                    lastError = result.exceptionOrNull()?.message,
                    retryCount = if (result.isSuccess) 0 else latestTask.retryCount + 1
                )
            )

            repository.insertLog(TaskLog(
                taskId = task.id,
                taskContent = "[手动执行] ${task.content}",
                botName = bot?.name ?: "未知机器人",
                chatName = chat?.name ?: "未知群组",
                status = if (result.isSuccess) LogStatus.SUCCESS else LogStatus.FAILED,
                errorMessage = result.exceptionOrNull()?.message
            ))
        }
    }

    fun retryFailedLog(log: TaskLog) {
        viewModelScope.launch {
            val task = repository.getTaskById(log.taskId)
            if (task != null) {
                sendTaskNow(task)
            }
        }
    }
}
