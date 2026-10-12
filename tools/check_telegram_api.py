"""用实际 Retrofit 接口和拦截器检查请求构造；不联网、不执行 Gradle、不启动 Android 应用。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/autosend"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group, artifact, version):
    matches = list((CACHE / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise RuntimeError(f"Missing cached {artifact} {version}; no downloads performed")
    return str(matches[0])


MODELS = '''
package com.autosend.data.models
data class Bot(val token: String)
data class Chat(val chatId: String)
enum class MessageParseMode { NONE, MARKDOWN_V2 }
data class PendingMessage(val botId: Long, val chatId: Long, val content: String,
    val parseMode: MessageParseMode = MessageParseMode.NONE)
'''
DAO = '''
package com.autosend.data.local
import com.autosend.data.models.*
interface AutoSendDao {
    suspend fun getBotById(id: Long): Bot?
    suspend fun getChatById(id: Long): Chat?
}
'''
CHECK = r'''
import com.autosend.data.remote.*
import com.autosend.data.models.*
import com.autosend.data.local.AutoSendDao
import com.autosend.data.repository.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.JsonParser
import java.net.UnknownHostException

private const val TOKEN = "123456789:TEST_TOKEN"
private class FakeDao : AutoSendDao {
    override suspend fun getBotById(id: Long) = Bot(TOKEN)
    override suspend fun getChatById(id: Long) = Chat("123456789")
}
private class FailingApi(val error: Exception, val failProbe: Boolean = true) : TelegramApi {
    override suspend fun sendMessage(token: String, request: TelegramMessageRequest): retrofit2.Response<TelegramResponse<TelegramMessage>> = throw error
    override suspend fun getMe(token: String): retrofit2.Response<TelegramResponse<TelegramUser>> {
        if (failProbe) throw error
        return retrofit2.Response.success(TelegramResponse(ok = true, result = TelegramUser(1)))
    }
    override suspend fun getChat(token: String, request: TelegramChatRequest): retrofit2.Response<TelegramResponse<TelegramChat>> = throw error
}

fun main(args: Array<String>) = runBlocking {
    val broken = args.singleOrNull() == "expect-broken"
    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    // 应用拦截器直接返回模拟响应，不调用 proceed，因此不会进行 DNS、连接或真实发送。
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        val result = when (request.url.pathSegments.last()) {
            "getMe" -> """{"id":1}"""
            "getChat" -> """{"id":123456789,"type":"private"}"""
            "sendMessage" -> """{"message_id":42,"chat":{"id":123456789,"type":"private"}}"""
            else -> error("Unexpected request path")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(("""{"ok":true,"result":$result}""").toResponseBody("application/json".toMediaType())).build()
    }.build()
    try {
        val api = Retrofit.Builder().baseUrl("https://api.telegram.org/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).validateEagerly(true).build().create(TelegramApi::class.java)
        for (method in listOf("getMe", "getChat", "sendMessage")) {
            try {
                when (method) {
                    "getMe" -> check(api.getMe(TOKEN).body()?.ok == true)
                    "getChat" -> check(api.getChat(TOKEN, TelegramChatRequest("123456789")).body()?.result?.type == "private")
                    "sendMessage" -> check(api.sendMessage(TOKEN, TelegramMessageRequest("123456789", "offline test")).body()?.result?.messageId == 42L)
                }
                check(!broken) { "Old URL regression was not reproduced" }
            } catch (e: IllegalArgumentException) {
                check(broken && e.message.orEmpty().contains("Malformed URL")) { "Unexpected request construction failure" }
                check(requests.isEmpty()) { "Malformed request must fail before interception or networking" }
            }
        }
        if (broken) {
            println("Old paths: all 3 methods reproduce Malformed URL before any request is sent")
        } else {
            check(requests.size == 3)
            requests.forEach { request ->
                val method = request.url.pathSegments.last()
                check(request.url.scheme == "https" && request.url.host == "api.telegram.org")
                check(request.url.encodedPath == "/bot$TOKEN/$method")
                check(request.method == if (method == "getMe") "GET" else "POST")
                if (method != "getMe") {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    check(body["chat_id"].asString == "123456789")
                    if (method == "sendMessage") check(body["text"].asString == "offline test")
                }
            }
            println("Fixed paths: all 3 methods construct correct HTTPS URLs, payloads and typed responses")
        }
        val message = PendingMessage(1, 1, "offline test")
        val invalid = IllegalArgumentException("Malformed URL: bot$TOKEN/getMe")
        val delivery = TelegramDeliveryClient(FailingApi(invalid), FakeDao())
        val sent = delivery.send(message) as TelegramDeliveryResult.Failure
        check(sent.kind == DeliveryFailureKind.MESSAGE && !sent.uncertain && !sent.reason.contains(TOKEN))
        val probe = delivery.probe(1)!!
        check(probe.kind == DeliveryFailureKind.MESSAGE && !probe.uncertain && !probe.reason.contains(TOKEN))
        val tested = TelegramDeliveryClient(FailingApi(invalid, failProbe = false), FakeDao()).test(1, 1)
        check(tested.isFailure && !tested.exceptionOrNull()!!.message.orEmpty().contains(TOKEN))
        val dns = TelegramDeliveryClient(FailingApi(UnknownHostException()), FakeDao()).send(message) as TelegramDeliveryResult.Failure
        check(dns.kind == DeliveryFailureKind.NETWORK && !dns.uncertain)
        try {
            TelegramDeliveryClient(FailingApi(CancellationException()), FakeDao()).send(message)
            error("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        println("Failure classification: malformed requests preserved without network retry or Token leakage; DNS and cancellation unchanged")
    } finally {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
'''


def main():
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Missing local JDK; no downloads performed")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0")]
    runtime = [jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
               jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
               jar("com.squareup.retrofit2", "retrofit", "2.11.0"),
               jar("com.squareup.retrofit2", "converter-gson", "2.11.0"),
               jar("com.squareup.okhttp3", "okhttp", "4.9.0"),
               jar("com.squareup.okio", "okio-jvm", "3.6.0"),
               jar("com.google.code.gson", "gson", "2.10.1"),
               jar("javax.inject", "javax.inject", "1"),
               jar("org.jetbrains", "annotations", "13.0")]
    with tempfile.TemporaryDirectory(prefix="autosend-telegram-api-check-") as directory:
        temp = Path(directory)
        for name, source in [("Models.kt", MODELS), ("Dao.kt", DAO), ("ApiCheck.kt", CHECK)]:
            (temp / name).write_text(source, encoding="utf-8")
        api = (SOURCE / "data/remote/TelegramApi.kt").read_text(encoding="utf-8-sig")
        for mode in ["expect-broken", "fixed"]:
            # 在临时副本重现旧写法，再验证仓库中的真实修复；不修改仓库或手机数据。
            variant = api.replace('("/bot{token}', '("bot{token}') if mode == "expect-broken" else api
            (temp / "TelegramApi.kt").write_text(variant, encoding="utf-8")
            output = temp / mode
            subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(runtime), "-d", str(output),
                            str(temp / "TelegramApi.kt"), str(SOURCE / "data/repository/TelegramDeliveryClient.kt"),
                            str(temp / "Models.kt"), str(temp / "Dao.kt"), str(temp / "ApiCheck.kt")], check=True)
            subprocess.run([java, "-cp", os.pathsep.join([str(output)] + runtime), "ApiCheckKt", mode], check=True)


if __name__ == "__main__":
    main()
