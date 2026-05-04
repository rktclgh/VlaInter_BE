package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.dto.LoginRequest
import com.cw.vlainter.domain.auth.service.AuthAccessAuditService
import com.cw.vlainter.domain.auth.service.AuthRateLimitService
import com.cw.vlainter.domain.auth.service.AuthService
import com.cw.vlainter.domain.auth.service.AuthProviderType
import com.cw.vlainter.domain.auth.service.KakaoAuthService
import com.cw.vlainter.domain.auth.service.LoginResult
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.exception.GlobalExceptionHandler
import com.cw.vlainter.global.security.AuthCookieManager
import com.cw.vlainter.global.security.ClientIpResolver
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@ExtendWith(MockitoExtension::class)
class AuthControllerTests {

    @Mock
    private lateinit var authService: AuthService

    @Mock
    private lateinit var kakaoAuthService: KakaoAuthService

    @Mock
    private lateinit var authCookieManager: AuthCookieManager

    @Mock
    private lateinit var authAccessAuditService: AuthAccessAuditService

    @Mock
    private lateinit var authRateLimitService: AuthRateLimitService

    private lateinit var mockMvc: MockMvc
    private val clientIpResolver = ClientIpResolver("127.0.0.1/32,::1/128", "X-Internal-Client-IP")
    private val directExecutor = Executor { runnable -> runnable.run() }

    @BeforeEach
    fun setUp() {
        val controller = AuthController(
            authService,
            kakaoAuthService,
            authCookieManager,
            authAccessAuditService,
            authRateLimitService,
            clientIpResolver,
            directExecutor
        )
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(jacksonObjectMapper()))
            .build()
    }

    @Test
    fun `login returns user info and auth cookies`() {
        val request = LoginRequest(
            email = "tester@vlainter.com",
            password = "Password123!",
            redirectUri = "https://app.vlainter.com/home"
        )
        val loginResult = LoginResult(
            userId = 1L,
            email = request.email,
            name = "Tester",
            role = UserRole.ADMIN,
            accessToken = "access-token",
            refreshToken = "refresh-token",
            redirectUri = request.redirectUri,
            sessionId = "session-1",
            authProvider = AuthProviderType.EMAIL
        )
        val accessCookie = ResponseCookie.from("vlainter_at", "access-token").httpOnly(true).path("/").build()
        val refreshCookie = ResponseCookie.from("vlainter_rt", "refresh-token").httpOnly(true).path("/").build()
        val clearAccessCookie = ResponseCookie.from("vlainter_at", "").httpOnly(true).path("/").maxAge(0).build()
        val clearRefreshCookie = ResponseCookie.from("vlainter_rt", "").httpOnly(true).path("/").maxAge(0).build()
        given(authService.login(request)).willReturn(loginResult)
        given(authCookieManager.createAccessTokenCookie("access-token")).willReturn(accessCookie)
        given(authCookieManager.createRefreshTokenCookie("refresh-token")).willReturn(refreshCookie)
        given(authCookieManager.clearAccessTokenCookie()).willReturn(clearAccessCookie)
        given(authCookieManager.clearRefreshTokenCookie()).willReturn(clearRefreshCookie)

        val result = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Client-IP", "127.0.0.1")
                .content(jacksonObjectMapper().writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.email").value(request.email))
            .andExpect(jsonPath("$.name").value("Tester"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.redirectUri").value(request.redirectUri))
            .andReturn()

        val setCookies = result.response.getHeaders(HttpHeaders.SET_COOKIE)
        assertEquals(4, setCookies.size)
        assertTrue(setCookies.any { it.startsWith("vlainter_at=") && it.contains("Max-Age=0") })
        assertTrue(setCookies.any { it.startsWith("vlainter_rt=") && it.contains("Max-Age=0") })
        assertTrue(setCookies.any { it.startsWith("vlainter_at=access-token") })
        assertTrue(setCookies.any { it.startsWith("vlainter_rt=refresh-token") })

        val successOrder = inOrder(authRateLimitService, authService)
        successOrder.verify(authRateLimitService).checkLoginAttempt(request.email, "127.0.0.1", true)
        successOrder.verify(authService).login(request)
        then(authCookieManager).should().createAccessTokenCookie("access-token")
        then(authCookieManager).should().createRefreshTokenCookie("refresh-token")
        then(authAccessAuditService).should(timeout(1000)).recordLogin(
            loginResult.sessionId,
            loginResult.userId,
            loginResult.email,
            loginResult.authProvider,
            "127.0.0.1",
            null
        )
    }

    @Test
    fun `login returns 400 when required json field is missing`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Client-IP", "127.0.0.1")
                .content("""{"email":"tester@vlainter.com"}""")
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(authService, authCookieManager, authRateLimitService)
    }

    @Test
    fun `login returns 401 on auth failure`() {
        val request = LoginRequest(email = "tester@vlainter.com", password = "WrongPassword!")
        given(authService.login(request))
            .willThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."))

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Client-IP", "127.0.0.1")
                .content(jacksonObjectMapper().writeValueAsString(request))
        )
            .andExpect(status().isUnauthorized)

        val failureOrder = inOrder(authRateLimitService, authService)
        failureOrder.verify(authRateLimitService).checkLoginAttempt(request.email, "127.0.0.1", true)
        failureOrder.verify(authService).login(request)
        verifyNoInteractions(authCookieManager, authAccessAuditService)
    }

    @Test
    fun `login still succeeds when audit executor rejects submission`() {
        val rejectingExecutor = Executor { throw RejectedExecutionException("queue full") }
        val controller = AuthController(
            authService,
            kakaoAuthService,
            authCookieManager,
            authAccessAuditService,
            authRateLimitService,
            clientIpResolver,
            rejectingExecutor
        )
        val localMockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(jacksonObjectMapper()))
            .build()
        val request = LoginRequest(email = "tester@vlainter.com", password = "Password123!")
        val loginResult = LoginResult(
            userId = 1L,
            email = request.email,
            name = "Tester",
            role = UserRole.USER,
            accessToken = "access-token",
            refreshToken = "refresh-token",
            redirectUri = null,
            sessionId = "session-1",
            authProvider = AuthProviderType.EMAIL
        )
        val accessCookie = ResponseCookie.from("vlainter_at", "access-token").httpOnly(true).path("/").build()
        val refreshCookie = ResponseCookie.from("vlainter_rt", "refresh-token").httpOnly(true).path("/").build()
        val clearAccessCookie = ResponseCookie.from("vlainter_at", "").httpOnly(true).path("/").maxAge(0).build()
        val clearRefreshCookie = ResponseCookie.from("vlainter_rt", "").httpOnly(true).path("/").maxAge(0).build()

        given(authService.login(request)).willReturn(loginResult)
        given(authCookieManager.createAccessTokenCookie("access-token")).willReturn(accessCookie)
        given(authCookieManager.createRefreshTokenCookie("refresh-token")).willReturn(refreshCookie)
        given(authCookieManager.clearAccessTokenCookie()).willReturn(clearAccessCookie)
        given(authCookieManager.clearRefreshTokenCookie()).willReturn(clearRefreshCookie)

        localMockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Client-IP", "127.0.0.1")
                .content(jacksonObjectMapper().writeValueAsString(request))
        )
            .andExpect(status().isOk)

        then(authAccessAuditService).shouldHaveNoInteractions()
    }
}
