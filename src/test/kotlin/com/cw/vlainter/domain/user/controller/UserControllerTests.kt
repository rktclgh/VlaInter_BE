package com.cw.vlainter.domain.user.controller

import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.service.UserService
import com.cw.vlainter.global.security.AuthCookieManager
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.web.server.ResponseStatusException

@ExtendWith(MockitoExtension::class)
class UserControllerTests {

    @Mock
    private lateinit var userService: UserService

    @Mock
    private lateinit var authCookieManager: AuthCookieManager

    @Mock
    private lateinit var loginSessionStore: LoginSessionStore

    @Mock
    private lateinit var response: HttpServletResponse

    @Test
    fun changeMyPasswordReturns200() {
        val principal = AuthPrincipal(
            userId = 1L,
            email = "user@vlainter.com",
            sessionId = "sid-1",
            role = UserRole.USER
        )
        val request = ChangeMyPasswordRequest(
            currentPassword = "old-password",
            newPassword = "New-password-123!"
        )
        val clearAccessCookie = ResponseCookie.from("vlainter_at", "").path("/").maxAge(0).build()
        val clearRefreshCookie = ResponseCookie.from("vlainter_rt", "").path("/").maxAge(0).build()
        org.mockito.BDDMockito.given(authCookieManager.clearAccessTokenCookie()).willReturn(clearAccessCookie)
        org.mockito.BDDMockito.given(authCookieManager.clearRefreshTokenCookie()).willReturn(clearRefreshCookie)

        val changedResponse = UserController(userService, authCookieManager, loginSessionStore)
            .changeMyPassword(principal, request, response)

        assertThat(changedResponse.statusCode.value()).isEqualTo(200)
        assertThat(changedResponse.body?.get("message")).isNotBlank()
        then(userService).should().changeMyPassword(principal, request)
        then(loginSessionStore).should().delete(principal.sessionId)
        then(authCookieManager).should().clearAccessTokenCookie()
        then(authCookieManager).should().clearRefreshTokenCookie()
    }

    @Test
    fun changeMyPasswordPropagatesBadRequestFromService() {
        val principal = AuthPrincipal(
            userId = 1L,
            email = "user@vlainter.com",
            sessionId = "sid-1",
            role = UserRole.USER
        )
        val request = ChangeMyPasswordRequest(
            currentPassword = "wrong-password",
            newPassword = "New-password-123!"
        )
        willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect."))
            .given(userService)
            .changeMyPassword(principal, request)

        val exception = assertThrows<ResponseStatusException> {
            UserController(userService, authCookieManager, loginSessionStore).changeMyPassword(principal, request, response)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(loginSessionStore).should(never()).delete(anyString())
    }
}
