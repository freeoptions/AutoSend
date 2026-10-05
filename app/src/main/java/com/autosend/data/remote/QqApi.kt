package com.autosend.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface QqApi {
    @POST("app/getAppAccessToken")
    suspend fun getAccessToken(
        @Body request: QqAccessTokenRequest
    ): Response<QqAccessTokenResponse>

    @POST("v2/groups/{groupOpenId}/messages")
    suspend fun sendGroupMessage(
        @Header("Authorization") authorization: String,
        @Path("groupOpenId") groupOpenId: String,
        @Body request: QqMessageRequest
    ): Response<QqMessageResponse>
}

data class QqAccessTokenRequest(
    val appId: String,
    val clientSecret: String
)

data class QqAccessTokenResponse(
    @SerializedName("access_token")
    val accessToken: String? = null,
    @SerializedName("expires_in")
    val expiresIn: String? = null,
    val code: Int? = null,
    val message: String? = null
) {
    fun isSuccessful(): Boolean = !accessToken.isNullOrBlank() && (code == null || code == 0)
}

data class QqMessageRequest(
    @SerializedName("msg_type")
    val messageType: Int = 0,
    val content: String
)

data class QqMessageResponse(
    val id: String? = null,
    val timestamp: String? = null,
    @SerializedName("err_code")
    val errorCode: Int? = null,
    val message: String? = null,
    @SerializedName("trace_id")
    val traceId: String? = null
) {
    fun isSuccessful(): Boolean = errorCode == null && !id.isNullOrBlank()

    fun errorMessage(): String {
        return message?.takeIf { it.isNotBlank() }
            ?: traceId?.let { "QQ 返回错误，Trace ID：$it" }
            ?: "QQ 返回未知错误"
    }
}
