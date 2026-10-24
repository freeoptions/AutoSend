package com.autosend.computer

import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val COMPUTER_FILE_LIMIT = 100_000_000L

data class ComputerFile(val name: String, val size: Long?, val mime: String)
data class UploadOutcome(val success: Boolean, val uncertain: Boolean, val message: String)

/** Independent LAN transport; no proxy selector, HTTP body/header logging, redirects or POST retries. */
class ComputerUploadClient internal constructor(builder: OkHttpClient.Builder? = null,
                                                private val diagnosticLog: (String) -> Unit = {}) {
    // Shared only by computer transfers, not by Telegram or other application traffic.
    private val initializing = System.nanoTime()
    internal val client = if (builder == null) sharedClient else configure(builder)
    private val clientInitMs = elapsedMs(initializing)

    fun upload(address: String, password: String, file: ComputerFile,
               open: () -> InputStream, progress: (Long) -> Unit,
               metadataMs: Long = 0, settingsMs: Long = 0): UploadAttempt {
        require(file.size == null || file.size in 0..COMPUTER_FILE_LIMIT)
        require(file.name.isNotBlank() && file.name.length <= 255 &&
            file.name.none { it.isISOControl() || it == '/' || it == '\\' } &&
            file.name != "." && file.name != "..")
        val trace = ComputerTransferTrace("upload", diagnosticLog).also {
            it.clientInitMs = clientInitMs; it.metadataMs = metadataMs; it.settingsMs = settingsMs
        }
        val attempt = UploadAttempt(file, trace)
        val body = object : RequestBody() {
            override fun contentType() = file.mime.toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaTypeOrNull()
            // Unknown providers are streamed with chunked encoding; never trust metadata for the limit.
            override fun contentLength() = -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val opening = System.nanoTime()
                val input = try { open() } finally { trace.sourceOpenMs += elapsedMs(opening) }
                input.use {
                    attempt.source.set(input)
                    try {
                        copyLimited(input, { bytes, count -> sink.write(bytes, 0, count) },
                            { count -> attempt.bytes.set(count); progress(count) },
                        { attempt.cancelled.get() || attempt.call?.isCanceled() == true })
                        attempt.bodyCompleted.set(true)
                    } finally { attempt.source.compareAndSet(input, null) }
                }
            }
        }
        attempt.prepare = {
            val authenticating = System.nanoTime()
            val auth = try { authorize(address, password, attempt, trace, verify = false) }
                finally { trace.authenticationMs = elapsedMs(authenticating) }
            // File GET status is not a documented existence API. Assign a new name directly.
            val targetFile = file.copy(name = renamedComputerFilename(file.name))
            attempt.destination = targetFile
            val request = Request.Builder().url(serverUrl(address).resolve("/__api/upload")!!)
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", targetFile.name, body).build())
                .tag(UploadAttempt::class.java, attempt)
                .tag(ComputerCallMetrics::class.java, trace.call(ComputerCallStage.UPLOAD))
                .header("Authorization", auth.header("POST", "/__api/upload")).build()
            attempt.call = attempt.attach(client.newCall(request))
        }
        return attempt
    }

    fun connectionTest(address: String, password: String): ComputerConnectionTest {
        val trace = ComputerTransferTrace("test", diagnosticLog)
        trace.clientInitMs = clientInitMs
        return ComputerConnectionTest(trace) { session ->
            val authenticating = System.nanoTime()
            val auth = try { authorize(address, password, session, trace, verify = true) }
                finally { trace.authenticationMs = elapsedMs(authenticating) }
            if (auth.challenged) "服务可达，认证已接受；未测试文件上传或落盘。"
            else "服务可达，但未要求认证，无法验证密码；未测试文件上传或落盘。"
        }
    }

    /** Upload needs only the challenge GET; the settings test additionally verifies the credentials. */
    private fun authorize(address: String, password: String, session: ComputerCallSession,
                          trace: ComputerTransferTrace, verify: Boolean): ComputerAuthorization {
        val root = Request.Builder().url(serverUrl(address)).get()
            .tag(ComputerCallMetrics::class.java, trace.call(ComputerCallStage.AUTH_CHALLENGE)).build()
        val auth = session.attach(client.newCall(root)).execute().use { response ->
            if (response.code in 200..299) return object : ComputerAuthorization {
                override val challenged = false
                override fun header(method: String, uri: String) = Credentials.basic("quicker", password, Charsets.UTF_8)
            }
            if (response.code != 401) throw ComputerAuthException("服务响应 HTTP ${response.code}，无法确认认证；文件未发送。")
            val challenges = response.challenges()
            val digest = challenges.firstOrNull { it.scheme.equals("Digest", true) }
            if (digest != null) ComputerDigest(digest.authParams, password)
            else {
                val basic = challenges.firstOrNull { it.scheme.equals("Basic", true) }
                    ?: throw ComputerAuthException("电脑未提供支持的认证质询；文件未发送。")
                object : ComputerAuthorization {
                    override fun header(method: String, uri: String) = Credentials.basic("quicker", password, basic.charset)
                }
            }
        }
        if (verify) session.attach(client.newCall(root.newBuilder()
            .tag(ComputerCallMetrics::class.java, trace.call(ComputerCallStage.AUTH_VERIFY))
            .header("Authorization", auth.header("GET", "/")).build()))
            .execute().use { response ->
                if (response.code == 401 || response.code == 403)
                    throw ComputerAuthException("认证被拒绝，请检查密码；文件未发送。")
                if (response.code !in 200..299)
                    throw ComputerAuthException("服务响应 HTTP ${response.code}，认证情况无法确认；文件未发送。")
            }
        return auth
    }

    companion object {
        private val sharedClient by lazy { configure(OkHttpClient.Builder()) }
        private fun configure(builder: OkHttpClient.Builder): OkHttpClient = builder
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .eventListenerFactory { call ->
                call.request().tag(ComputerCallMetrics::class.java)?.listener() ?: EventListener.NONE
            }.build()

        fun serverUrl(address: String): HttpUrl {
            val url = expandComputerAddress(address).toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == "/" && url.query == null && url.fragment == null) {
                "请填写 HTTPS 服务根地址，不能包含账号、密码、路径或参数"
            }
            return url
        }
    }
}

class FileLimitException : IOException("File exceeds limit")
internal class ComputerPreflightException(message: String) : IOException(message)

internal fun renamedComputerFilename(original: String): String {
    val dot = original.lastIndexOf('.')
    val extension = if (dot > 0 && dot < original.lastIndex) original.substring(dot) else ""
    val stem = if (extension.isEmpty()) original else original.substring(0, dot)
    val suffix = " (${UUID.randomUUID().toString().replace("-", "")})"
    val available = 255 - extension.length - suffix.length
    if (available < 1) throw ComputerPreflightException("文件扩展名过长，无法自动重命名，本次未上传。")
    val prefix = stem.take(available).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    return prefix + suffix + extension
}

/** Reads at most limit + 1 bytes and never writes the over-limit byte. No file cache. */
internal fun copyLimited(input: InputStream, write: (ByteArray, Int) -> Unit,
                         progress: (Long) -> Unit, cancelled: () -> Boolean) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        if (cancelled()) throw IOException("Cancelled")
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), COMPUTER_FILE_LIMIT - total + 1).toInt())
        if (count < 0) return
        if (count == 0) continue
        if (total + count > COMPUTER_FILE_LIMIT) throw FileLimitException()
        write(buffer, count)
        total += count
        progress(total)
    }
}

class ComputerConnectionTest internal constructor(private val trace: ComputerTransferTrace,
    private val run: (ComputerCallSession) -> String) : ComputerCallSession() {
    fun execute(): String {
        var result = "failed"
        return try { run(this).also { result = "completed" } }
        catch (e: ComputerAuthException) { e.message ?: "认证失败。" }
        catch (_: Exception) { "连接测试未完成，请检查网络、地址和 HTTPS 证书。" }
        finally { trace.finish(if (cancelled.get()) "cancelled" else result) }
    }
}

class UploadAttempt internal constructor(private val file: ComputerFile, private val trace: ComputerTransferTrace) : ComputerCallSession() {
    internal var destination = file
    @Volatile internal var call: Call? = null
    internal lateinit var prepare: () -> Unit
    val bytes = AtomicLong(0)
    val requestStarted = AtomicBoolean(false)
    internal val source = AtomicReference<InputStream?>(null)
    internal val bodyCompleted = AtomicBoolean(false)
    fun closeSource() { runCatching { source.getAndSet(null)?.close() } }
    private fun withDestination(result: UploadOutcome): UploadOutcome =
        if (destination.name != file.name) result.copy(message = result.message + "\n电脑文件名：${destination.name}")
        else result

    fun execute(): UploadOutcome {
        val outcome = try {
            if (cancelled.get()) throw IOException("Cancelled")
            prepare()
            call!!.execute().use { response ->
                val validating = System.nanoTime()
                val result = try { validateUploadResponse(response, destination, bytes.get()) }
                    finally { trace.validationMs = elapsedMs(validating) }
                if (result.success && !bodyCompleted.get())
                    withDestination(UploadOutcome(false, true, "电脑响应时文件尚未完整发送，结果未知，请先检查电脑。"))
                else withDestination(result)
            }
        } catch (e: Exception) {
            val unknown = requestStarted.get()
            withDestination(UploadOutcome(false, unknown, when {
                e is ComputerPreflightException -> e.message.orEmpty()
                e is ComputerAuthException -> e.message.orEmpty()
                e is FileLimitException -> "文件超过 100,000,000 字节，已停止上传。"
                cancelled.get() -> "上传已取消。"
                !unknown -> "连接失败，请检查电脑服务、网络与 HTTPS 证书。"
                else -> "传输中断或响应未完整收到。"
            } + if (unknown) "结果未知，文件可能已经送达；请先检查电脑再重试。" else "文件未发送。"))
        }
        trace.finish(when { outcome.success -> "success"; cancelled.get() -> "cancelled"
            outcome.uncertain -> "unknown"; else -> "failed" }, bytes.get())
        return outcome
    }
}

internal fun validateUploadResponse(response: Response, file: ComputerFile, bytes: Long): UploadOutcome {
    // Never expose server body or exception text (may contain credentials / private paths).
    if (response.code == 408) return UploadOutcome(false, true,
        "电脑报告请求超时，结果未知，文件可能已送达；请先检查电脑再重试。")
    if (response.code in 400..499) return UploadOutcome(false, false, when (response.code) {
        401, 403 -> "认证被拒绝，请检查密码和电脑服务权限。"
        409 -> "电脑拒绝上传：请检查文件冲突；本次不会自动重试。"
        413 -> "电脑拒绝上传：文件过大。"
        else -> "电脑拒绝上传（HTTP ${response.code}），请检查服务；本次不会自动重试。"
    })
    val text = response.body?.source()?.let { source ->
        val buffer = okio.Buffer()
        while (buffer.size <= 65_536) {
            if (source.read(buffer, minOf(8192L, 65_537 - buffer.size)) < 0) break
        }
        if (buffer.size > 65_536) null else buffer.readUtf8()
    }
    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    val valid = runCatching {
        val files = json!!.getAsJsonArray("files")
        val result = files.single().asJsonObject
        response.code == 200 && json["success"].isJsonPrimitive &&
            json["success"].asJsonPrimitive.isBoolean && json["success"].asBoolean &&
            // Quicker 2.3.17 real responses omit fieldName. Check it only when supplied.
            (!result.has("fieldName") || result["fieldName"].asString == "file") &&
            result["name"].asString == file.name &&
            result["size"].asJsonPrimitive.isNumber &&
            result["size"].asBigDecimal.compareTo(java.math.BigDecimal.valueOf(bytes)) == 0 &&
            result["path"].asString == "/${file.name}"
    }.getOrDefault(false)
    if (valid) return UploadOutcome(true, false, "已发送到电脑")
    val rejected = runCatching {
        json != null && ((json.has("success") && json["success"].asJsonPrimitive.isBoolean &&
            !json["success"].asBoolean) || (response.code >= 500 &&
            response.body?.contentType()?.subtype == "problem+json" && json["status"].asInt == response.code))
    }.getOrDefault(false)
    return if (rejected) UploadOutcome(false, false, "电脑报告上传失败（HTTP ${response.code}），请检查同名文件、磁盘和服务配置。")
    else UploadOutcome(false, true, "未收到有效的逐文件成功结果（HTTP ${response.code}）。结果未知，文件可能已经送达；请先检查电脑再重试。")
}
