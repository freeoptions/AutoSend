package com.autosend.computer

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class ComputerCallStage { AUTH_CHALLENGE, AUTH_VERIFY, UPLOAD }

/** Only durations, numeric counts, fixed enum names and HTTP status; never request/response data. */
internal class ComputerTransferTrace(
    private val operation: String,
    private val write: (String) -> Unit,
    private val now: () -> Long = System::nanoTime
) {
    private val id = sequence.incrementAndGet()
    private val started = now()
    private val finished = AtomicBoolean(false)
    private val calls = mutableListOf<ComputerCallMetrics>()
    var metadataMs = 0L
    var clientInitMs = 0L
    var settingsMs = 0L
    var authenticationMs = 0L
    var sourceOpenMs = 0L
    var validationMs = 0L

    fun call(stage: ComputerCallStage): ComputerCallMetrics =
        ComputerCallMetrics(stage, now).also { calls.add(it) }

    fun finish(result: String, bytes: Long = 0) {
        if (!finished.compareAndSet(false, true)) return
        val line = "id=$id op=$operation result=$result total_ms=${elapsedMs(started, now())} " +
            "client_init_ms=$clientInitMs metadata_ms=$metadataMs settings_ms=$settingsMs auth_ms=$authenticationMs " +
            "source_open_ms=$sourceOpenMs validate_ms=$validationMs bytes=$bytes calls=${calls.size} " +
            calls.joinToString(" ") { it.summary() }
        // Diagnostics must never change whether a file is sent or how its result is classified.
        runCatching { write(line) }
    }

    private companion object { val sequence = AtomicLong() }
}

internal fun elapsedMs(start: Long, end: Long = System.nanoTime()) =
    ((end - start).coerceAtLeast(0) / 1_000_000)

internal class ComputerCallMetrics(private val stage: ComputerCallStage, private val now: () -> Long) {
    private var started = 0L
    private var ended = 0L
    private var dnsStarted = 0L
    private var connectStarted = 0L
    private var tlsStarted = 0L
    private var bodyStarted = 0L
    private var sentAt = 0L
    private var dnsMs = 0L
    private var connectMs = 0L
    private var tlsMs = 0L
    private var bodyMs = 0L
    private var waitMs = 0L
    private var connects = 0
    private var acquired = false
    private var code = 0
    private var failed = false

    fun listener(): EventListener = object : EventListener() {
        override fun callStart(call: Call) { started = now() }
        override fun dnsStart(call: Call, domainName: String) { dnsStarted = now() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMs += elapsedMs(dnsStarted, now()); dnsStarted = 0
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects++; connectStarted = now()
        }
        override fun secureConnectStart(call: Call) { tlsStarted = now() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMs += elapsedMs(tlsStarted, now()); tlsStarted = 0
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectMs += elapsedMs(connectStarted, now()); connectStarted = 0
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
                                   protocol: Protocol?, ioe: IOException) {
            connectMs += elapsedMs(connectStarted, now()); connectStarted = 0
        }
        override fun connectionAcquired(call: Call, connection: Connection) { acquired = true }
        override fun requestHeadersStart(call: Call) {
            call.request().tag(UploadAttempt::class.java)?.requestStarted?.set(true)
        }
        override fun requestHeadersEnd(call: Call, request: Request) { sentAt = now() }
        override fun requestBodyStart(call: Call) { bodyStarted = now() }
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            bodyMs += elapsedMs(bodyStarted, now()); bodyStarted = 0; sentAt = now()
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            code = response.code
            if (sentAt != 0L) waitMs += elapsedMs(sentAt, now())
        }
        override fun callEnd(call: Call) { ended = now() }
        override fun callFailed(call: Call, ioe: IOException) { failed = true; ended = now() }
    }

    fun summary(): String {
        val end = ended.takeIf { it != 0L } ?: now()
        fun duration(value: Long, active: Long) = value + if (active == 0L) 0 else elapsedMs(active, end)
        val connection = when { !acquired -> "not_acquired"; connects > 0 -> "new"; else -> "reused" }
        return "${stage.name}{ms=${if (started == 0L) 0 else elapsedMs(started, end)}," +
            "dns_ms=${duration(dnsMs, dnsStarted)},connect_ms=${duration(connectMs, connectStarted)}," +
            "tls_ms=${duration(tlsMs, tlsStarted)},body_ms=${duration(bodyMs, bodyStarted)}," +
            "response_wait_ms=$waitMs,connection=$connection,http=$code,failed=$failed}"
    }
}
