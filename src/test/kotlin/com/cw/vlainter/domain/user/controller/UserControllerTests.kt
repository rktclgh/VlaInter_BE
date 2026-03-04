package com.cw.vlainter.domain.user.controller

import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.user.service.UserService
import com.cw.vlainter.global.security.AuthCookieManager
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@ExtendWith(MockitoExtension::class)
class UserControllerTests {

    @Mock
    private lateinit var userService: UserService

    @Mock
    private lateinit var authCookieManager: AuthCookieManager

    @Mock
    private lateinit var loginSessionStore: LoginSessionStore

    @Test
    fun changeMyPasswordReturns200() {
        val principal = AuthPrincipal(
            userId = 1L,
            email = "user@vlainter.com",
            sessionId = "sid-1"
        )
        val request = ChangeMyPasswordRequest(
            currentPassword = "old-password",
            newPassword = "New-password-123!"
        )

        val response = UserController(userService, authCookieManager, loginSessionStore).changeMyPassword(principal, request)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.get("message")).isNotBlank()
        then(userService).should().changeMyPassword(principal, request)
        then(loginSessionStore).should().delete(principal.sessionId)
    }

    @Test
    fun changeMyPasswordPropagatesBadRequestFromService() {
        val principal = AuthPrincipal(
            userId = 1L,
            email = "user@vlainter.com",
            sessionId = "sid-1"
        )
        val request = ChangeMyPasswordRequest(
            currentPassword = "wrong-password",
            newPassword = "New-password-123!"
        )
        willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect."))
            .given(userService)
            .changeMyPassword(principal, request)

        val exception = assertThrows<ResponseStatusException> {
            UserController(userService, authCookieManager, loginSessionStore).changeMyPassword(principal, request)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
