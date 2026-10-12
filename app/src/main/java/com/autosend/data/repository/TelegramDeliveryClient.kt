package com.autosend.data.repository

import com.autosend.data.models.MessageParseMode
import com.autosend.data.models.PendingMessage
import com.autosend.data.local.AutoSendDao
import com.autosend.data.remote.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.net.ConnectException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

enum class DeliveryFailureKind { NETWORK, RATE_LIMIT, BOT, TARGET, MESSAGE }

sealed class TelegramDeliveryResult {
    data class Success(val messageId: Long, val chatType: String?) : TelegramDeliveryResult()
    data class Failure(
        val kind: DeliveryFailureKind,
        val reason: String,
        val retryAfterSeconds: Long = 0,
        val migratedChatId: Long? = null,
        val uncertain: Boolean = false
    ) : TelegramDeliveryResult()
}

@Singleton
class TelegramDeliveryClient @Inject constructor(
    private val api: TelegramApi,
    private val dao: AutoSendDao
) {
    suspend fun send(message: PendingMessage): TelegramDeliveryResult {
        val bot = dao.getBotById(message.botId)
            ?: return failure(DeliveryFailureKind.BOT, "机器人配置已删除")
        val chat = dao.getChatById(message.chatId)
            ?: return failure(DeliveryFailureKind.TARGET, "发送目标已删除")
        return try {
            val response = api.sendMessage(bot.token.trim(), TelegramMessageRequest(
                chat.chatId.trim(), message.content,
                if (message.parseMode == MessageParseMode.MARKDOWN_V2) "MarkdownV2" else null
            ))
            val body = response.body()
            val sent = body?.result
            if (response.isSuccessful && body?.ok == true && sent != null && sent.messageId > 0 && sent.chat != null) {
                TelegramDeliveryResult.Success(sent.messageId, sent.chat?.type)
            } else {
                classify(response, bot.token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            requestConfigurationFailure()
        } catch (e: Exception) {
            networkFailure(e)
        }
    }

    /** 无发送副作用的线路探测；不能用 Android 的联网状态代替 Telegram 可达性。 */
    suspend fun probe(botId: Long): TelegramDeliveryResult.Failure? {
        val bot = dao.getBotById(botId)
            ?: return failure(DeliveryFailureKind.BOT, "机器人配置已删除")
        return try {
            val response = api.getMe(bot.token.trim())
            if (response.isSuccessful && response.body()?.ok == true && response.body()?.result != null) null
            else classify(response, bot.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            requestConfigurationFailure()
        } catch (e: Exception) {
            networkFailure(e).copy(uncertain = false)
        }
    }

    suspend fun test(botId: Long, chatId: Long): Result<Unit> {
        probe(botId)?.let { return Result.failure(Exception(it.reason)) }
        val bot = dao.getBotById(botId) ?: return Result.failure(Exception("机器人不存在"))
        val chat = dao.getChatById(chatId) ?: return Result.failure(Exception("目标不存在"))
        return try {
            val response = api.getChat(bot.token.trim(), TelegramChatRequest(chat.chatId.trim()))
            if (response.isSuccessful && response.body()?.ok == true && response.body()?.result != null) {
                Result.success(Unit)
            } else Result.failure(Exception(classify(response, bot.token).reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(Exception(requestConfigurationFailure().reason))
        } catch (e: Exception) {
            Result.failure(Exception(networkFailure(e).reason))
        }
    }

    private fun classify(response: Response<*>, token: String): TelegramDeliveryResult.Failure {
        val body = response.body() as? TelegramResponse<*>
            ?: runCatching {
                Gson().fromJson(response.errorBody()?.string(), TelegramResponse::class.java)
            }.getOrNull()
        val code = body?.errorCode ?: response.code()
        val reason = (body?.description ?: "Telegram 响应异常（HTTP ${response.code()}）")
            .replace(token, "[已隐藏]").take(300)
        val kind = when {
            // 代理可能返回 HTML 错误页；不能误判为目标永久失效。
            body?.errorCode == null -> DeliveryFailureKind.NETWORK
            code == 429 -> DeliveryFailureKind.RATE_LIMIT
            code == 401 || code == 404 -> DeliveryFailureKind.BOT
            code == 403 -> DeliveryFailureKind.TARGET
            code == 400 && (reason.contains("chat not found", true) || reason.contains("not enough rights", true)) -> DeliveryFailureKind.TARGET
            code == 400 -> DeliveryFailureKind.MESSAGE
            code >= 500 || response.isSuccessful || code == 408 || code == 407 -> DeliveryFailureKind.NETWORK
            else -> DeliveryFailureKind.MESSAGE
        }
        return TelegramDeliveryResult.Failure(
            kind, reason, body?.parameters?.retryAfter ?: if (code == 429) 60L else 0L,
            body?.parameters?.migrateToChatId,
            uncertain = kind == DeliveryFailureKind.NETWORK
        )
    }

    private fun failure(kind: DeliveryFailureKind, reason: String) = TelegramDeliveryResult.Failure(kind, reason)

    // 请求构造失败尚未联网，不能伪装成网络超时并进入线路退避；也不能泄露带 Token 的异常原文。
    private fun requestConfigurationFailure() = failure(DeliveryFailureKind.MESSAGE,
        "Telegram 请求配置无效，请检查机器人配置或更新应用；请求尚未发送")

    private fun networkFailure(error: Exception) = TelegramDeliveryResult.Failure(
        DeliveryFailureKind.NETWORK,
        when (error) {
            is UnknownHostException -> "无法解析 Telegram 地址，请检查网络或代理"
            is ConnectException -> "无法连接 Telegram，请检查网络或代理"
            else -> "Telegram 连接中断或响应超时，发送结果未确认"
        },
        uncertain = error !is UnknownHostException && error !is ConnectException
    )
}
