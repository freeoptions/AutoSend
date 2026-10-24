"""Offline checks: compile the real upload client, intercept requests, parse Kotlin/XML.

No Gradle, downloads, Android launch, LAN connection, or real upload.
Uses the existing JDK and Gradle dependency cache only.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from check_telegram_api import jar
from check_telegram_queue import JAVA_PARSER

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/autosend"

CHECK = r'''
package com.autosend.computer

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Proxy

private fun json(name: String = "截图.txt", size: String = "3", success: String = "true") =
    """{"success":$success,"files":[{"name":"$name","path":"/$name","size":$size}]}"""

private fun response(request: Request, text: String, code: Int = 200, type: String = "application/json") =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(text.toResponseBody(type.toMediaType())).build()

private class Generated(private var remaining: Long) : InputStream() {
    override fun read() = if (remaining-- > 0) 1 else -1
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = minOf(remaining, length.toLong()).toInt()
        remaining -= count
        return count
    }
}

fun main() {
    val builders = mutableListOf<OkHttpClient>()
    var calls = 0
    var posts = 0
    var opened = 0
    var fileGets = 0
    var fileGetStatus = 200
    val uploadedNames = mutableListOf<String>()
    var mode = "ok"
    val builder = OkHttpClient.Builder().addInterceptor { chain ->
        // Never call proceed: no DNS, socket or actual computer upload.
        calls++
        val r = chain.request()
        check(r.url.scheme == "https" && r.url.host == "192-168-1-100.lan.quicker.cc" && r.url.port == 17831)
        if (r.method == "POST") posts++
        if (mode == "dns") throw java.net.UnknownHostException("private data must not leak")
        if (mode == "lost" && r.method == "POST") {
            r.tag(UploadAttempt::class.java)!!.requestStarted.set(true)
            throw java.io.IOException("secret URL must not leak")
        }
        if (r.method == "GET" && r.url.encodedPath != "/") {
            // Adversarial fixture: a file GET may return 200 even for random names.
            // It must never be used as an existence check, regardless of response status.
            fileGets++
            response(r, "<html>generic page</html>", fileGetStatus, "text/html")
        } else if (r.method == "GET") {
            check(r.url.encodedPath == "/" && r.body == null)
            if (mode in listOf("digest", "wrong", "unsupported") && r.header("Authorization") == null) {
                response(r, "", 401).newBuilder().header("WWW-Authenticate",
                    "Digest realm=\"test,realm\", nonce=\"nonce123\", algorithm=" +
                        (if (mode == "unsupported") "SHA-999" else "MD5") + ", qop=\"auth\", opaque=\"value\"").build()
            } else if (mode == "basic" && r.header("Authorization") == null) {
                response(r, "", 401).newBuilder().header("WWW-Authenticate", "Basic realm=\"test\"").build()
            } else if (mode in listOf("digest", "wrong")) {
                check(r.header("Authorization")!!.startsWith("Digest "))
                check(r.header("Authorization")!!.contains("uri=\"/\""))
                check(r.header("Authorization")!!.contains("nc=00000001"))
                response(r, "", if (mode == "wrong") 401 else 200)
            } else response(r, "", 200)
        } else {
            check(r.method == "POST" && r.url.encodedPath == "/__api/upload" && r.url.query == null)
            if (mode == "wrong") {
                check(r.header("Authorization")!!.startsWith("Digest "))
                response(r, "", 401)
            } else {
            if (mode == "digest") {
                val header = r.header("Authorization")!!
                check(header.startsWith("Digest ") && header.contains("uri=\"/__api/upload\"") && header.contains("nc=00000001"))
                val cnonce = Regex("cnonce=\"([^\"]+)\"").find(header)!!.groupValues[1]
                fun hash(v: String) = java.security.MessageDigest.getInstance("MD5").digest(v.toByteArray())
                    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                val expected = hash(hash("quicker:test,realm:fake-only") + ":nonce123:00000001:$cnonce:auth:" + hash("POST:/__api/upload"))
                check(header.contains("response=\"$expected\""))
            } else check(r.header("Authorization") == Credentials.basic("quicker", "fake-only", Charsets.UTF_8))
            val body = r.body as MultipartBody
            check(body.type == MultipartBody.FORM && body.parts.size == 1)
            val part = body.parts.single()
            val destination = r.tag(UploadAttempt::class.java)!!.destination
            check(part.headers!!["Content-Disposition"] == "form-data; name=\"file\"; filename=\"${destination.name}\"")
            check(destination.name != "截图.txt" && destination.name.endsWith(".txt"))
            check(Regex(" \\([0-9a-f]{32}\\)\\.txt$").containsMatchIn(destination.name))
            uploadedNames.add(destination.name)
            check(part.body.isOneShot() && part.body.contentLength() == -1L)
            if (mode != "early") {
                val data = Buffer()
                part.body.writeTo(data)
                check(data.readUtf8() == "abc")
            }
            when (mode) {
                "duplicate" -> response(r, "{}", 409, "application/problem+json")
                "bad" -> response(r, "<html>OK</html>")
                "early" -> response(r, json(name = destination.name, size = "0"))
                else -> response(r, json(name = destination.name))
            }
            }
        }
    }
    val logs = mutableListOf<String>()
    val client = ComputerUploadClient(builder, logs::add)
    val shared = ComputerUploadClient().client
    check(shared === ComputerUploadClient().client) { "Default transfers must reuse the same transport/pool" }
    check(client.client !== shared) { "Offline fixtures must not touch the production transport" }
    val configured = builder.build().also { builders.add(it) }
    check(configured.proxy == Proxy.NO_PROXY && !configured.retryOnConnectionFailure &&
        !configured.followRedirects && !configured.followSslRedirects)
    check(configured.callTimeoutMillis == 900_000 && configured.readTimeoutMillis == 120_000)
    val address = "https://192-168-1-100.lan.quicker.cc:17831"
    val file = ComputerFile("截图.txt", 3, "text/plain")
    fun upload(source: ComputerFile = file) = client.upload(address, "fake-only", source,
        { opened++; ByteArrayInputStream("abc".toByteArray()) }, {}).execute()
    try {
        check(upload().success)
        mode = "duplicate"; check(upload().let { !it.success && !it.uncertain })
        mode = "bad"; check(upload().uncertain)
        mode = "early"; check(upload().let { it.uncertain && it.message.contains("尚未完整发送") })
        mode = "dns"; check(upload().let { !it.uncertain && !it.message.contains("private data") })
        mode = "lost"; check(upload().let { it.uncertain && !it.message.contains("secret URL") })
        check(calls == 11 && posts == 5 && fileGets == 0) { "Unexpected request/retry: $calls / $posts / $fileGets" }
        mode = "ok"; check(client.connectionTest(address, "fake-only").execute().contains("未要求认证"))
        var before = calls
        mode = "digest"; check(upload().success && calls - before == 2)
        check(logs.last().contains("AUTH_CHALLENGE") && !logs.last().contains("AUTH_VERIFY"))
        before = calls
        val previousWrongPosts = posts
        mode = "wrong"; check(upload().let { !it.success && !it.uncertain && it.message.contains("认证被拒绝") } && calls - before == 2)
        check(posts == previousWrongPosts + 1) { "Rejected credentials must not trigger a POST retry" }
        check(client.connectionTest(address, "fake-only").execute().contains("认证被拒绝"))
        check(logs.last().contains("AUTH_VERIFY") && logs.last().contains("result=failed"))
        before = calls
        mode = "unsupported"; check(!upload().success && calls - before == 1)
        before = calls
        mode = "basic"; check(upload().success && calls - before == 2)
        mode = "digest"; check(client.connectionTest(address, "fake-only").execute().contains("认证已接受"))
        check(logs.last().contains("AUTH_VERIFY") && logs.last().contains("result=completed"))
        val vector = ComputerDigest(mapOf("realm" to "testrealm@host.com", "nonce" to
            "dcd98b7102dd2f0e8b11d0f600bfb0c093", "qop" to "auth"), "Circle Of Life", "Mufasa", "0a4f113b")
        check(vector.header("GET", "/dir/index.html").contains("response=\"6629fae49393a05397450978507c4ef1\""))
        val cancelled = client.upload(address, "fake-only", file, { error("must not open") }, {})
        cancelled.cancel(); check(!cancelled.execute().success)
        mode = "ok"
        for (status in listOf(200, 206, 304, 403, 404, 416)) {
            fileGetStatus = status
            val previousCalls = calls
            val previousPosts = posts
            val previousOpens = opened
            check(upload().let { it.success && it.message.contains("电脑文件名：截图 (") })
            check(calls - previousCalls == 2 && posts == previousPosts + 1 && opened == previousOpens + 1)
            check(fileGets == 0) { "File existence probes must not block upload" }
        }
        check(uploadedNames.distinct().size == uploadedNames.size) { "Repeated shares must get distinct destination names" }
        check(upload(file.copy(name = "测试 #+%.txt")).success)
        mode = "lost"
        check(upload().let { it.uncertain && it.message.contains("电脑文件名：截图 (") })
        check(renamedComputerFilename("photo.png").matches(Regex("photo \\([0-9a-f]{32}\\)\\.png")))
        check(renamedComputerFilename("no-extension").startsWith("no-extension ("))
        check(renamedComputerFilename("a".repeat(250) + ".jpeg").let { it.length == 255 && it.endsWith(".jpeg") })

        val r = Request.Builder().url(address).build()
        fun validate(text: String, code: Int = 200, type: String = "application/json") =
            response(r, text, code, type).use { validateUploadResponse(it, file, 3) }
        check(validate(json()).success)
        check(validate(json().replace("\"name\":", "\"fieldName\":\"file\",\"name\":" )).success)
        check(validate(json().replace("\"name\":", "\"fieldName\":\"other\",\"name\":" )).uncertain)
        check(validate(json().replace("\"name\":", "\"fieldName\":null,\"name\":" )).uncertain)
        // Exact user-supplied Quicker 2.3.17 responses; do not derive fixtures from our expected schema.
        val ascii = """{"success":true,"files":[{"name":"autosend-probe-2.txt","path":"/autosend-probe-2.txt","url":"/autosend-probe-2.txt","size":10,"contentType":"text/plain"}]}"""
        response(r, ascii).use {
            check(validateUploadResponse(it, ComputerFile("autosend-probe-2.txt", 10, "text/plain"), 10).success)
        }
        val unicode = """{"success":true,"files":[{"name":"autosend-clip-\u6D4B\u8BD5 2.txt","path":"/autosend-clip-\u6D4B\u8BD5 2.txt","url":"/autosend-clip-%E6%B5%8B%E8%AF%95%202.txt","size":22,"contentType":"text/plain"}]}"""
        response(r, unicode).use {
            check(validateUploadResponse(it, ComputerFile("autosend-clip-测试 2.txt", 22, "text/plain"), 22).success)
        }
        check(validate(json(size = "4")).uncertain)
        check(validate(json(size = "3.5")).uncertain)
        check(validate(json(size = "\"3\"")).uncertain)
        check(validate(json(name = "other.txt")).uncertain)
        check(!validate(json(success = "false")).uncertain)
        check(validate("""{"success":true,"files":[]}""").uncertain)
        check(validate(json(success = "\"true\"")).uncertain)
        check(validate(json(), 201).uncertain)
        check(validate(json(), 302).uncertain)
        check(validate("x".repeat(70_000)).uncertain)
        check(!validate("{}", 401).uncertain)
        check(validate("{}", 408).uncertain)
        check(validate("{}", 500).uncertain)
        check(!validate("""{"status":500,"title":"write failed"}""", 500, "application/problem+json").uncertain)
        for (invalid in listOf("http://example.invalid:17831", "https://quicker:secret@example.invalid",
                "$address/path", "$address?path=other", "$address#fragment", "", "192.168.", "1.100",
                "192.168.256.1", "192.168.1.-1", "192.168.1.100:0", "192.168.1.100:65536",
                "192.168.1.100:", "192.168.1.100/path", "192.168.1.100?path=other")) {
            check(runCatching { ComputerUploadClient.serverUrl(invalid) }.isFailure)
        }
        check(computerAddressForEditing("") == "192.168.")
        for ((input, expected) in listOf(
                "192.168.1.100" to address,
                " 192.168.1.100:17831 " to address,
                "10.0.0.100" to "https://10-0-0-100.lan.quicker.cc:17831",
                "172.16.0.100:17832" to "https://172-16-0-100.lan.quicker.cc:17832",
                "192.168.1.100:443" to "https://192-168-1-100.lan.quicker.cc",
                "192.168.1.100:65535" to "https://192-168-1-100.lan.quicker.cc:65535")) {
            val normalized = ComputerUploadClient.serverUrl(input).toString().trimEnd('/')
            check(normalized == expected) { "Address input did not expand correctly" }
            val editing = computerAddressForEditing(normalized)
            check(ComputerUploadClient.serverUrl(editing).toString().trimEnd('/') == normalized)
        }
        check(computerAddressForEditing(address) == "192.168.1.100")
        check(computerAddressForEditing("https://example.invalid:17831") == "https://example.invalid:17831")
        check(ComputerUploadClient.serverUrl("https://example.invalid:17831").host == "example.invalid")
        mode = "digest"
        val shorthandPosts = posts
        check(client.upload("192.168.1.100", "fake-only", file,
            { ByteArrayInputStream("abc".toByteArray()) }, {}).execute().success)
        check(posts == shorthandPosts + 1)
        check(client.connectionTest("192.168.1.100", "fake-only").execute().contains("认证已接受"))
        check(runCatching { client.upload(address, "fake-only", file.copy(size = COMPUTER_FILE_LIMIT + 1),
            { error("must not open") }, {}) }.isFailure)
        check(runCatching { client.upload(address, "fake-only", file.copy(name = "../x"),
            { error("must not open") }, {}) }.isFailure)
        for (size in listOf(0L, 3L, COMPUTER_FILE_LIMIT, COMPUTER_FILE_LIMIT + 1)) {
            var written = 0L
            val result = runCatching { copyLimited(Generated(size), { _, n -> written += n }, {}, { false }) }
            check(written <= COMPUTER_FILE_LIMIT)
            if (size <= COMPUTER_FILE_LIMIT) check(result.isSuccess && written == size)
            else check(result.exceptionOrNull() is FileLimitException)
        }
        check(runCatching { copyLimited(Generated(100), { _, _ -> error("must not write") }, {}, { true }) }.isFailure)
        check(logs.any { it.contains("result=success") } && logs.any { it.contains("result=unknown") })
        check(logs.any { it.contains("result=cancelled") } && logs.any { it.contains("result=failed") })
        for (log in logs) {
            check(!log.contains("fake-only") && !log.contains(address) && !log.contains("截图") &&
                !log.contains("Authorization") && !log.contains("nonce123") && !log.contains("secret URL") &&
                !log.contains("private data") && !log.contains("abc")) { "Diagnostics must contain no payload, URL or credentials" }
            check(log.contains("auth_ms=") && log.contains("source_open_ms=") && log.contains("total_ms="))
        }
        // Logging failures cannot change a successful upload into a failure or trigger retries.
        mode = "ok"
        val noLog = ComputerUploadClient(builder) { error("logger unavailable") }
        check(noLog.upload(address, "fake-only", file, { ByteArrayInputStream("abc".toByteArray()) }, {}).execute().success)
        // Deterministic EventListener timing, without DNS or sockets.
        var clock = 1_000_000L
        val timingLogs = mutableListOf<String>()
        val timing = ComputerTransferTrace("upload", timingLogs::add, { clock })
        val metrics = timing.call(ComputerCallStage.UPLOAD)
        val listener = metrics.listener()
        val dummy = client.client.newCall(Request.Builder().url(address).build())
        val socket = java.net.InetSocketAddress.createUnresolved("redacted.invalid", 17831)
        listener.callStart(dummy)
        listener.dnsStart(dummy, "must-not-log.invalid"); clock += 10_000_000
        listener.dnsEnd(dummy, "must-not-log.invalid", emptyList())
        listener.connectStart(dummy, socket, Proxy.NO_PROXY); clock += 5_000_000
        listener.secureConnectStart(dummy); clock += 20_000_000
        listener.secureConnectEnd(dummy, null)
        listener.connectEnd(dummy, socket, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        val fakeConnection = java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { _, _, _ -> error("Connection details must not be inspected") } as Connection
        listener.connectionAcquired(dummy, fakeConnection)
        listener.requestHeadersEnd(dummy, dummy.request())
        listener.requestBodyStart(dummy); clock += 7_000_000
        listener.requestBodyEnd(dummy, 3); clock += 11_000_000
        response(dummy.request(), json()).use { listener.responseHeadersEnd(dummy, it) }
        listener.callEnd(dummy)
        timing.finish("success", 3); timing.finish("success", 3)
        check(timingLogs.size == 1)
        check(timingLogs.single().contains("dns_ms=10,connect_ms=25,tls_ms=20,body_ms=7,response_wait_ms=11,connection=new,http=200"))
        check(!timingLogs.single().contains("must-not-log") && !timingLogs.single().contains("redacted.invalid"))
        val reused = timing.call(ComputerCallStage.AUTH_CHALLENGE)
        val reusedListener = reused.listener()
        reusedListener.callStart(dummy); reusedListener.connectionAcquired(dummy, fakeConnection); reusedListener.callEnd(dummy)
        check(reused.summary().contains("connection=reused"))
        println("PASS: editable IPv4/default port/custom HTTPS compatibility and shorthand requests; shared LAN transport; Digest; redacted timings; Quicker responses; UUID names; no retries; cancellation and stream limit")
    } finally {
        configured.dispatcher.executorService.shutdownNow()
        configured.connectionPool.evictAll()
    }
}
'''


def main():
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Missing existing JDK; no download attempted")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0")]
    runtime = [jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
               jar("com.squareup.okhttp3", "okhttp", "4.9.0"),
               jar("com.squareup.okio", "okio-jvm", "3.6.0"),
               jar("com.google.code.gson", "gson", "2.10.1"),
               jar("org.jetbrains", "annotations", "13.0")]
    with tempfile.TemporaryDirectory(prefix="autosend-computer-check-") as directory:
        temp = Path(directory)
        check = temp / "ComputerCheck.kt"
        check.write_text(CHECK, encoding="utf-8")
        output = temp / "classes"
        subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(runtime), "-d", str(output),
                        str(SOURCE / "computer/ComputerUploadClient.kt"),
                        str(SOURCE / "computer/ComputerAuthentication.kt"),
                        str(SOURCE / "computer/ComputerAddress.kt"),
                        str(SOURCE / "computer/ComputerTransferTrace.kt"), str(check)], check=True)
        subprocess.run([java, "-cp", os.pathsep.join([str(output)] + runtime),
                        "com.autosend.computer.ComputerCheckKt"], check=True)
        parser = temp / "KotlinSyntaxCheck.java"
        parser.write_text(JAVA_PARSER, encoding="utf-8")
        files = list((SOURCE / "computer").glob("*.kt")) + [SOURCE / "ui/screens/ConfigScreen.kt", SOURCE / "MainActivity.kt"]
        subprocess.run([java, "-cp", os.pathsep.join(compiler), str(parser), *map(str, files)], check=True)
    for name in ["app/src/main/AndroidManifest.xml", "app/src/main/res/xml/computer_backup_rules.xml",
                 "app/src/main/res/xml/computer_data_extraction_rules.xml"]:
        ET.parse(ROOT / name)
    print("PASS: Kotlin syntax and manifest/backup XML (not Android type checking or device verification)")


if __name__ == "__main__":
    main()
