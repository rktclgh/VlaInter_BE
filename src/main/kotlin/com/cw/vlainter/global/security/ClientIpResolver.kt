package com.cw.vlainter.global.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.net.InetAddress

@Component
class ClientIpResolver(
    @Value("\${app.security.trusted-proxies:127.0.0.1/32,::1/128}")
    trustedProxyRanges: String,
    @Value("\${app.security.client-ip-header:X-Internal-Client-IP}")
    private val clientIpHeaderName: String
) {
    private val trustedNetworks = trustedProxyRanges
        .split(",")
        .mapNotNull { parseCidr(it.trim()) }

    fun resolve(request: HttpServletRequest): String = resolveDetail(request).clientIp

    fun resolveDetail(request: HttpServletRequest): Resolution {
        val remoteAddr = request.remoteAddr?.trim().orEmpty()
        if (remoteAddr.isBlank()) {
            return Resolution("unknown", Source.UNKNOWN, trustedProxy = false)
        }
        if (!isTrustedProxy(remoteAddr)) {
            return Resolution(remoteAddr, Source.DIRECT_REMOTE_ADDR, trustedProxy = false)
        }

        val headerIp = extractHeaderIp(request, clientIpHeaderName)
        if (headerIp != null) {
            return Resolution(headerIp, Source.TRUSTED_PROXY_HEADER, trustedProxy = true)
        }

        return Resolution(remoteAddr, Source.TRUSTED_PROXY_FALLBACK, trustedProxy = true)
    }

    private fun extractHeaderIp(request: HttpServletRequest, headerName: String): String? {
        return request.getHeader(headerName)
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() && isParsableIp(it) }
    }

    private fun isTrustedProxy(address: String): Boolean {
        val inetAddress = parseInetAddress(address) ?: return false
        return trustedNetworks.any { it.contains(inetAddress) }
    }

    private fun isParsableIp(address: String): Boolean = parseInetAddress(address) != null

    private fun parseInetAddress(value: String): InetAddress? {
        if (!looksLikeIpLiteral(value)) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun looksLikeIpLiteral(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return looksLikeIpv4Literal(trimmed) || looksLikeIpv6Literal(trimmed)
    }

    private fun looksLikeIpv4Literal(value: String): Boolean {
        val octets = value.split(".")
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotBlank() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull() in 0..255
        }
    }

    private fun looksLikeIpv6Literal(value: String): Boolean {
        if (!value.contains(':')) return false
        return value.all { char ->
            char.isDigit() ||
                char.lowercaseChar() in 'a'..'f' ||
                char == ':' ||
                char == '.'
        }
    }

    private fun parseCidr(cidr: String): IpNetwork? {
        if (cidr.isBlank()) return null
        val parts = cidr.split("/")
        val address = parseInetAddress(parts[0]) ?: return null
        val prefixLength = parts.getOrNull(1)?.toIntOrNull()
            ?: if (address.address.size == 4) 32 else 128
        return IpNetwork(address, prefixLength)
    }

    private data class IpNetwork(
        val networkAddress: InetAddress,
        val prefixLength: Int
    ) {
        fun contains(address: InetAddress): Boolean {
            val networkBytes = networkAddress.address
            val addressBytes = address.address
            if (networkBytes.size != addressBytes.size) return false

            val bits = networkBytes.size * 8
            if (prefixLength < 0 || prefixLength > bits) return false

            if (prefixLength == 0) return true

            val shift = bits - prefixLength
            val networkValue = BigInteger(1, networkBytes).shiftRight(shift)
            val addressValue = BigInteger(1, addressBytes).shiftRight(shift)
            return networkValue == addressValue
        }
    }

    data class Resolution(
        val clientIp: String,
        val source: Source,
        val trustedProxy: Boolean
    ) {
        val isReliableForSecurity: Boolean
            get() = source == Source.DIRECT_REMOTE_ADDR || source == Source.TRUSTED_PROXY_HEADER
    }

    enum class Source {
        DIRECT_REMOTE_ADDR,
        TRUSTED_PROXY_HEADER,
        TRUSTED_PROXY_FALLBACK,
        UNKNOWN
    }
}
