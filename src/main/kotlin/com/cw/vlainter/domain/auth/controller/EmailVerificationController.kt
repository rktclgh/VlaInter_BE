package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.dto.SendVerificationEmailRequest
import com.cw.vlainter.domain.auth.dto.VerifyEmailCodeRequest
import com.cw.vlainter.domain.auth.service.EmailVerificationService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/email-verification")
class EmailVerificationController(
    private val emailVerificationService: EmailVerificationService
) {
    @PostMapping("/send")
    fun sendVerificationEmail(
        @Valid
        @RequestBody request: SendVerificationEmailRequest
    ): ResponseEntity<Map<String, Any>> {
        val result = emailVerificationService.sendVerificationCode(request.email)
        return ResponseEntity.ok(
            mapOf(
                "message" to "인증 코드가 발송되었습니다.",
                "expiresInSeconds" to result.expiresInSeconds
            )
        )
    }

    @PostMapping("/verify")
    fun verifyCode(
        @Valid
        @RequestBody request: VerifyEmailCodeRequest
    ): ResponseEntity<Map<String, Any>> {
        val result = emailVerificationService.verifyCode(request.email, request.code)
        val message = if (result.verified) {
            "이메일 인증이 완료되었습니다."
        } else {
            "이메일 인증에 실패했습니다."
        }
        return ResponseEntity.ok(
            mapOf(
                "message" to message,
                "verified" to result.verified
            )
        )
    }
}
