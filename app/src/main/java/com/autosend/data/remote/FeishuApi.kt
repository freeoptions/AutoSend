package com.autosend.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface FeishuApi {
    @POST
    suspend fun sendMessage(
        @Url webhookUrl: String,
        @Body request: FeishuMessageRequest
    ): Response<FeishuResponse>
}

data class FeishuMessageRequest(
    val timestamp: String? = null,
    val sign: String? = null,
    @SerializedName("msg_type")
    val messageType: String = "text",
    val content: FeishuTextContent
)

data class FeishuTextContent(
    val text: String
)

data class FeishuResponse(
    val code: Int? = null,
    val msg: String? = null,
    @SerializedName("StatusCode")
    val legacyStatusCode: Int? = null,
    @SerializedName("StatusMessage")
    val legacyStatusMessage: String? = null
) {
    fun isSuccessful(): Boolean = code == 0 || legacyStatusCode == 0

    fun errorMessage(): String {
        return msg?.takeIf { it.isNotBlank() }
            ?: legacyStatusMessage?.takeIf { it.isNotBlank() }
            ?: "飞书返回未知错误"
    }
}
