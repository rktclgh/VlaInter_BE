package com.cw.vlainter.global.config.properties

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.email-verification")
data class EmailVerificationProperties(
    var codeExpSeconds: Long = 300,
    var resendCooldownSeconds: Long = 60,
    var codeLength: Int = 6
) {
    @PostConstruct
    fun validate() {
        require(codeExpSeconds > 0) { "app.email-verification.code-exp-seconds 는 0보다 커야 합니다." }
        require(resendCooldownSeconds >= 0) { "app.email-verification.resend-cooldown-seconds 는 0 이상이어야 합니다." }
        require(codeLength in 4..10) { "app.email-verification.code-length 는 4~10 범위여야 합니다." }
    }
}
