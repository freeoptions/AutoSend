package com.autosend.computer

import okhttp3.Call
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal class ComputerAuthException(message: String) : IOException(message)

/** Holds only the current request, so cancelling also cancels the authentication preflight. */
open class ComputerCallSession {
    @Volatile private var activeCall: Call? = null
    val cancelled = AtomicBoolean(false)
    internal fun attach(call: Call): Call {
        activeCall = call
        if (cancelled.get()) call.cancel()
        return call
    }
    open fun cancel() { cancelled.set(true); activeCall?.cancel() }
}

internal interface ComputerAuthorization {
    fun header(method: String, uri: String): String
    val challenged: Boolean get() = true
}

/** Quicker 2.3.17 delivery contract: Digest MD5, qop=auth (RFC 7616).
 * Each attempt owns its nonce/cnonce/count; no global credential or challenge cache.
 */
internal class ComputerDigest(
    private val params: Map<String?, String>,
    private val password: String,
    private val username: String = "quicker",
    private val cnonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
) : ComputerAuthorization {
    private val realm = params["realm"] ?: throw ComputerAuthException("电脑认证质询缺少 realm。")
    private val nonce = params["nonce"] ?: throw ComputerAuthException("电脑认证质询缺少 nonce。")
    private val charset: Charset = if (params["charset"].equals("UTF-8", true)) Charsets.UTF_8 else Charsets.ISO_8859_1
    private var count = 0
    init {
        if (!params["algorithm"].orEmpty().ifEmpty { "MD5" }.equals("MD5", true) ||
            params["qop"].orEmpty().split(',').none { it.trim().equals("auth", true) } ||
            params["userhash"].equals("true", true)) {
            throw ComputerAuthException("电脑要求的 Digest 方式尚不支持，需要 MD5 / qop=auth。")
        }
        if (realm.isEmpty() || nonce.isEmpty()) throw ComputerAuthException("电脑认证质询无效。")
    }

    override fun header(method: String, uri: String): String {
        val nc = (++count).toString(16).padStart(8, '0')
        fun hash(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(charset)).hex()
        val ha1 = hash("$username:$realm:$password")
        val ha2 = hash("$method:$uri")
        val digest = hash("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        return "Digest username=${quote(username)}, realm=${quote(realm)}, nonce=${quote(nonce)}, " +
            "uri=${quote(uri)}, algorithm=MD5, qop=auth, nc=$nc, cnonce=${quote(cnonce)}, response=${quote(digest)}" +
            (params["opaque"]?.let { ", opaque=${quote(it)}" } ?: "")
    }
}

private fun quote(value: String): String {
    if (value.any { it.isISOControl() }) throw ComputerAuthException("电脑认证质询无效。")
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
