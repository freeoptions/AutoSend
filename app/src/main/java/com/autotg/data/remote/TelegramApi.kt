package com.autotg.data.remote

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Path

interface TelegramApi {
    @FormUrlEncoded
    @POST("bot{token}/sendMessage")
    suspend fun sendMessage(
        @Path("token") botToken: String,
        @Field("chat_id") chatId: String,
        @Field("text") text: String
    ): Response<TelegramResponse>
}

data class TelegramResponse(
    val ok: Boolean,
    val description: String? = null,
    val error_code: Int? = null
)
