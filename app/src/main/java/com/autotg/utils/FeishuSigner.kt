package com.autotg.utils

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FeishuSigner {
    fun sign(timestampSeconds: Long, secret: String): String {
        val stringToSign = "$timestampSeconds\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(stringToSign.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
    }
}
