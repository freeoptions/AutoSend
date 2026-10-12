package com.autosend.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TelegramApi {
    @POST("/bot{token}/sendMessage")
    suspend fun sendMessage(@Path("token") token: String, @Body request: TelegramMessageRequest): Response<TelegramResponse<TelegramMessage>>

    @GET("/bot{token}/getMe")
    suspend fun getMe(@Path("token") token: String): Response<TelegramResponse<TelegramUser>>

    @POST("/bot{token}/getChat")
    suspend fun getChat(@Path("token") token: String, @Body request: TelegramChatRequest): Response<TelegramResponse<TelegramChat>>
}

data class TelegramMessageRequest(
    @SerializedName("chat_id") val chatId: String,
    val text: String,
    @SerializedName("parse_mode") val parseMode: String? = null
)
data class TelegramChatRequest(@SerializedName("chat_id") val chatId: String)
data class TelegramResponse<T>(
    val ok: Boolean = false,
    val result: T? = null,
    @SerializedName("error_code") val errorCode: Int? = null,
    val description: String? = null,
    val parameters: TelegramParameters? = null
)
data class TelegramParameters(
    @SerializedName("retry_after") val retryAfter: Long? = null,
    @SerializedName("migrate_to_chat_id") val migrateToChatId: Long? = null
)
data class TelegramMessage(@SerializedName("message_id") val messageId: Long, val chat: TelegramChat?)
data class TelegramUser(val id: Long)
data class TelegramChat(val id: Long, val type: String)
