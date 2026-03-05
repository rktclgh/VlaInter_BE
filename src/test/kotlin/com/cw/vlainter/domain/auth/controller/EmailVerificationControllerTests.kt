package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.service.EmailVerificationService
import com.cw.vlainter.domain.auth.service.SendVerificationResult
import com.cw.vlainter.domain.auth.service.VerifyCodeResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

@ExtendWith(MockitoExtension::class)
class EmailVerificationControllerTests {

    @Mock
    private lateinit var emailVerificationService: EmailVerificationService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = EmailVerificationController(emailVerificationService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    @Test
    fun sendRequestReturns200AndExpiry() {
        val email = "songchih@icloud.com"
        given(emailVerificationService.sendVerificationCode(email))
            .willReturn(SendVerificationResult(expiresInSeconds = 300))

        mockMvc.perform(
            post("/api/auth/email-verification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(mapOf("email" to email)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").isNotEmpty)
            .andExpect(jsonPath("$.expiresInSeconds").value(300))

        then(emailVerificationService).should().sendVerificationCode(email)
    }

    @Test
    fun missingEmailFieldReturns400() {
        mockMvc.perform(
            post("/api/auth/email-verification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(emailVerificationService)
    }

    @Test
    fun cooldownExceededReturns429() {
        val email = "songchih@icloud.com"
        given(emailVerificationService.sendVerificationCode(email))
            .willThrow(ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many requests"))

        mockMvc.perform(
            post("/api/auth/email-verification/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(mapOf("email" to email)))
        )
            .andExpect(status().isTooManyRequests)

        then(emailVerificationService).should().sendVerificationCode(email)
    }

    @Test
    fun verifyRequestReturns200AndVerifiedTrue() {
        val email = "songchih@icloud.com"
        val code = "123456"
        given(emailVerificationService.verifyCode(email, code))
            .willReturn(VerifyCodeResult(verified = true))

        mockMvc.perform(
            post("/api/auth/email-verification/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(mapOf("email" to email, "code" to code)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").isNotEmpty)
            .andExpect(jsonPath("$.verified").value(true))

        then(emailVerificationService).should().verifyCode(email, code)
    }

    @Test
    fun verifyRequestMissingFieldsReturns400() {
        mockMvc.perform(
            post("/api/auth/email-verification/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"songchih@icloud.com"}""")
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(emailVerificationService)
    }

    @Test
    fun invalidOrExpiredCodeReturns400() {
        val email = "songchih@icloud.com"
        val code = "123456"
        given(emailVerificationService.verifyCode(email, code))
            .willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired code"))

        mockMvc.perform(
            post("/api/auth/email-verification/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(mapOf("email" to email, "code" to code)))
        )
            .andExpect(status().isBadRequest)

        then(emailVerificationService).should().verifyCode(email, code)
    }
}
