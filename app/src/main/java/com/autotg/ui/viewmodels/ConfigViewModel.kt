package com.autotg.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.repository.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val repository: TelegramRepository
) : ViewModel() {

    val bots: StateFlow<List<Bot>> = repository.getAllBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chats: StateFlow<List<Chat>> = repository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addBot(name: String, token: String) {
        viewModelScope.launch {
            repository.insertBot(Bot(name = name, token = token))
        }
    }

    fun deleteBot(bot: Bot) {
        viewModelScope.launch {
            repository.deleteBot(bot)
        }
    }

    fun addChat(name: String, chatId: String) {
        viewModelScope.launch {
            repository.insertChat(Chat(name = name, chatId = chatId))
        }
    }

    fun deleteChat(chat: Chat) {
        viewModelScope.launch {
            repository.deleteChat(chat)
        }
    }
}
