package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.service.PasswordRecoveryService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
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
class PasswordRecoveryControllerTests {

    @Mock
    private lateinit var passwordRecoveryService: PasswordRecoveryService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = PasswordRecoveryController(passwordRecoveryService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    @Test
    fun sendTemporaryPasswordReturns200() {
        val payload = mapOf(
            "email" to "user@vlainter.com",
            "name" to "User Name"
        )

        mockMvc.perform(
            post("/api/auth/password/temporary")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(payload))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").isNotEmpty)

        then(passwordRecoveryService).should().sendTemporaryPassword("user@vlainter.com", "User Name")
    }

    @Test
    fun sendTemporaryPasswordMissingFieldReturns400() {
        mockMvc.perform(
            post("/api/auth/password/temporary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"user@vlainter.com"}""")
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(passwordRecoveryService)
    }

    @Test
    fun sendTemporaryPasswordReturns400WhenIdentityDoesNotMatch() {
        willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and name do not match."))
            .given(passwordRecoveryService)
            .sendTemporaryPassword("user@vlainter.com", "Wrong Name")

        mockMvc.perform(
            post("/api/auth/password/temporary")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    jacksonObjectMapper().writeValueAsString(
                        mapOf(
                            "email" to "user@vlainter.com",
                            "name" to "Wrong Name"
                        )
                    )
                )
        )
            .andExpect(status().isBadRequest)

        then(passwordRecoveryService).should().sendTemporaryPassword("user@vlainter.com", "Wrong Name")
    }
}
