package com.cw.vlainter.global.exception

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTests {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(TestExceptionController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun responseStatusExceptionIsWrappedIntoApiErrorResponse() {
        mockMvc.perform(get("/test-errors/bad-request"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").isNotEmpty)
            .andExpect(jsonPath("$.path").value("/test-errors/bad-request"))
    }

    @Test
    fun missingRequiredRequestBodyFieldReturnsUnifiedBadRequestBody() {
        mockMvc.perform(
            post("/test-errors/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(mapOf("email" to "user@vlainter.com")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun unhandledExceptionReturns500WithUnifiedBody() {
        mockMvc.perform(get("/test-errors/unhandled"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun noResourceFoundReturns404WithUnifiedBody() {
        mockMvc.perform(get("/test-errors/missing-resource"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.path").value("/test-errors/missing-resource"))
    }

    @Test
    fun methodNotSupportedReturns405WithUnifiedBody() {
        mockMvc.perform(post("/test-errors/get-only"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.path").value("/test-errors/get-only"))
    }

    @RestController
    class TestExceptionController {
        @GetMapping("/test-errors/bad-request")
        fun badRequest(): String {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request")
        }

        @PostMapping("/test-errors/body")
        fun body(@RequestBody request: RequiredBodyRequest): String {
            return request.email
        }

        @GetMapping("/test-errors/unhandled")
        fun unhandled(): String {
            throw IllegalStateException("unhandled")
        }

        @GetMapping("/test-errors/missing-resource")
        fun missingResource(): String {
            throw NoResourceFoundException(HttpMethod.GET, "/test-errors/missing-resource")
        }

        @GetMapping("/test-errors/get-only")
        fun getOnly(): String = "ok"
    }

    data class RequiredBodyRequest(
        val email: String,
        val name: String
    )
}
