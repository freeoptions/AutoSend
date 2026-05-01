package com.autotg.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
import com.autotg.data.repository.TelegramRepository
import com.autotg.utils.WorkManagerHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: TelegramRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTask>> = repository.getAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bots: StateFlow<List<Bot>> = repository.getAllBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chats: StateFlow<List<Chat>> = repository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createTask(botId: Long, chatId: Long, content: String, scheduledTime: Long) {
        viewModelScope.launch {
            val task = ScheduledTask(
                botId = botId,
                chatId = chatId,
                content = content,
                scheduledTime = scheduledTime
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
            val updatedTask = task.copy(isEnabled = enabled)
            repository.updateTask(updatedTask)
            if (enabled) {
                WorkManagerHelper.scheduleTask(context, updatedTask)
            } else {
                WorkManagerHelper.cancelTask(context, updatedTask.id)
            }
        }
    }
}
