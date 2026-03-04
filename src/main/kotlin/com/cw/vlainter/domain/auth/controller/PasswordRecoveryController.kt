package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.dto.SendTemporaryPasswordRequest
import com.cw.vlainter.domain.auth.service.PasswordRecoveryService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/password")
class PasswordRecoveryController(
    private val passwordRecoveryService: PasswordRecoveryService
) {
    @PostMapping("/temporary")
    fun sendTemporaryPassword(
        @Valid
        @RequestBody request: SendTemporaryPasswordRequest
    ): ResponseEntity<Map<String, String>> {
        passwordRecoveryService.sendTemporaryPassword(request.email, request.name)
        return ResponseEntity.ok(mapOf("message" to "임시 비밀번호를 이메일로 발송했습니다."))
    }
}
