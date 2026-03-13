package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.global.security.SensitiveValueSanitizer

internal object AuthLogSanitizer {
    fun hash(value: String?): String = SensitiveValueSanitizer.hash(value)
}
