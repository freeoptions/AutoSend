package com.autosend.computer

internal const val COMPUTER_DEFAULT_IP_PREFIX = "192.168."
internal const val COMPUTER_DEFAULT_PORT = 17831
private val ipv4Input = Regex("([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?::([0-9]{1,5}))?")
private val quickerHost = Regex("([0-9]{1,3}(?:-[0-9]{1,3}){3})\\.lan\\.quicker\\.cc")

/** IP shorthand still uses Quicker's HTTPS domain and normal certificate/hostname validation. */
internal fun expandComputerAddress(input: String): String {
    val text = input.trim()
    if (text.contains("://")) return text
    val match = ipv4Input.matchEntire(text) ?: throw IllegalArgumentException("请填写完整 IPv4 或 HTTPS 根地址")
    val octets = match.groupValues[1].split('.').map { it.toInt().also { n -> require(n in 0..255) } }
    val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: COMPUTER_DEFAULT_PORT
    require(port in 1..65535)
    return "https://${octets.joinToString("-")}.lan.quicker.cc:$port"
}

/** Keep existing/custom HTTPS addresses usable; only shorten the standard Quicker address. */
internal fun computerAddressForEditing(savedAddress: String): String {
    if (savedAddress.isBlank()) return COMPUTER_DEFAULT_IP_PREFIX
    val url = runCatching { ComputerUploadClient.serverUrl(savedAddress) }.getOrNull() ?: return savedAddress
    val host = quickerHost.matchEntire(url.host) ?: return savedAddress
    val ip = host.groupValues[1].replace('-', '.')
    if (ip.split('.').any { it.toInt() !in 0..255 }) return savedAddress
    return ip + if (url.port == COMPUTER_DEFAULT_PORT) "" else ":${url.port}"
}
