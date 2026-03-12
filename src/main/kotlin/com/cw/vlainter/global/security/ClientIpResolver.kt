package com.cw.vlainter.global.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.net.InetAddress

@Component
class ClientIpResolver(
    @Value("\${app.security.trusted-proxies:127.0.0.1/32,::1/128}")
    trustedProxyRanges: String
) {
    private val trustedNetworks = trustedProxyRanges
        .split(",")
        .mapNotNull { parseCidr(it.trim()) }

    fun resolve(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr?.trim().orEmpty()
        if (remoteAddr.isBlank()) return "unknown"
        if (!isTrustedProxy(remoteAddr)) return remoteAddr

        request.getHeader("CF-Connecting-IP")
            ?.trim()
            ?.takeIf { it.isNotBlank() && isParsableIp(it) }
            ?.let { return it }

        val forwarded = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() && isParsableIp(it) }
        return forwarded ?: remoteAddr
    }

    private fun isTrustedProxy(address: String): Boolean {
        val inetAddress = parseInetAddress(address) ?: return false
        return trustedNetworks.any { it.contains(inetAddress) }
    }

    private fun isParsableIp(address: String): Boolean = parseInetAddress(address) != null

    private fun parseInetAddress(value: String): InetAddress? {
        return runCatching { InetAddress.getByName(value) }.getOrNull()
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
}
