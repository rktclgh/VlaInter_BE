package com.cw.vlainter.global.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SensitiveValueSanitizer {
    fun hash(value: String?): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return "-"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
