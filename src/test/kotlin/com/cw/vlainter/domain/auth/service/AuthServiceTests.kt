package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.auth.dto.LoginRequest
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.JwtTokenProvider
import com.cw.vlainter.global.security.LoginSessionStore
import com.cw.vlainter.global.security.RedirectUriValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AuthServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Mock
    private lateinit var loginSessionStore: LoginSessionStore

    @Mock
    private lateinit var redirectUriValidator: RedirectUriValidator

    private fun authService(): AuthService = AuthService(
        userRepository = userRepository,
        passwordEncoder = passwordEncoder,
        jwtTokenProvider = jwtTokenProvider,
        loginSessionStore = loginSessionStore,
        redirectUriValidator = redirectUriValidator
    )

    @Test
    fun `login 성공 시 토큰을 발급하고 세션을 생성한다`() {
        val user = createUser()
        val request = LoginRequest(
            email = user.email,
            password = "Password123!",
            redirectUri = "https://app.vlainter.com/interview"
        )

        var accessTokenSessionId: String? = null
        var refreshTokenSessionId: String? = null

        given(userRepository.findByEmail(user.email)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(request.password, user.password)).willReturn(true)
        given(redirectUriValidator.validate(request.redirectUri)).willReturn(request.redirectUri)
        given(
            jwtTokenProvider.createAccessToken(
                anyLongMatcher(),
                anyStringMatcher(),
                anyStringMatcher()
            )
        )
            .willAnswer { invocation ->
                accessTokenSessionId = invocation.getArgument(2)
                "access-token"
            }
        given(
            jwtTokenProvider.createRefreshToken(
                anyLongMatcher(),
                anyStringMatcher()
            )
        )
            .willAnswer { invocation ->
                refreshTokenSessionId = invocation.getArgument(1)
                "refresh-token"
            }

        val result = authService().login(request)

        assertThat(result.userId).isEqualTo(user.id)
        assertThat(result.email).isEqualTo(user.email)
        assertThat(result.name).isEqualTo(user.name)
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.redirectUri).isEqualTo(request.redirectUri)
        assertThat(accessTokenSessionId).isNotBlank()
        assertThat(refreshTokenSessionId).isNotBlank()
        assertThat(accessTokenSessionId).isEqualTo(refreshTokenSessionId)

        then(loginSessionStore).should().create(accessTokenSessionId!!, user.id, "refresh-token")
    }

    @Test
    fun `login 실패 - 존재하지 않는 이메일`() {
        val request = LoginRequest(email = "missing@vlainter.com", password = "Password123!")
        given(userRepository.findByEmail(request.email)).willReturn(Optional.empty())

        assertUnauthorized { authService().login(request) }
        verifyNoInteractions(passwordEncoder, jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login 실패 - 비밀번호 불일치`() {
        val user = createUser()
        val request = LoginRequest(email = user.email, password = "WrongPassword!")

        given(userRepository.findByEmail(user.email)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(request.password, user.password)).willReturn(false)

        assertUnauthorized { authService().login(request) }
        verifyNoInteractions(jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login 실패 - 비활성 계정`() {
        val blockedUser = createUser(status = UserStatus.BLOCKED)
        val request = LoginRequest(email = blockedUser.email, password = "Password123!")

        given(userRepository.findByEmail(blockedUser.email)).willReturn(Optional.of(blockedUser))
        given(passwordEncoder.matches(request.password, blockedUser.password)).willReturn(true)

        assertUnauthorized { authService().login(request) }
        verifyNoInteractions(jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login 실패 - 허용되지 않은 redirect_uri 는 세션 생성 전에 차단한다`() {
        val user = createUser()
        val request = LoginRequest(
            email = user.email,
            password = "Password123!",
            redirectUri = "https://evil.example.com/callback"
        )

        given(userRepository.findByEmail(user.email)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(request.password, user.password)).willReturn(true)
        given(redirectUriValidator.validate(request.redirectUri))
            .willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 redirect_uri 입니다."))

        val exception = assertThrows<ResponseStatusException> { authService().login(request) }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).isEqualTo("허용되지 않은 redirect_uri 입니다.")
        verifyNoInteractions(jwtTokenProvider, loginSessionStore)
    }

    @Test
    fun `refresh 실패 - 유효하지 않은 토큰`() {
        val refreshToken = "invalid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(false)

        assertUnauthorized { authService().refresh(refreshToken) }
        verifyNoInteractions(loginSessionStore, userRepository)
    }

    @Test
    fun `refresh 실패 - 세션 불일치 시 세션을 삭제한다`() {
        val refreshToken = "refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(1L)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.validateRefreshToken("sid-1", 1L, refreshToken)).willReturn(false)

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().delete("sid-1")
    }

    @Test
    fun `refresh 실패 - 사용자 없음`() {
        val refreshToken = "refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(1L)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.validateRefreshToken("sid-1", 1L, refreshToken)).willReturn(true)
        given(userRepository.findById(1L)).willReturn(Optional.empty())

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().validateRefreshToken("sid-1", 1L, refreshToken)
        then(loginSessionStore).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `refresh 실패 - 비활성 계정`() {
        val refreshToken = "refresh-token"
        val blockedUser = createUser(status = UserStatus.BLOCKED)

        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(blockedUser.id)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.validateRefreshToken("sid-1", blockedUser.id, refreshToken)).willReturn(true)
        given(userRepository.findById(blockedUser.id)).willReturn(Optional.of(blockedUser))

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().validateRefreshToken("sid-1", blockedUser.id, refreshToken)
        then(loginSessionStore).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `refresh 성공 시 토큰 재발급과 refresh 회전을 수행한다`() {
        val refreshToken = "refresh-token"
        val user = createUser()

        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(user.id)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.validateRefreshToken("sid-1", user.id, refreshToken)).willReturn(true)
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(jwtTokenProvider.createAccessToken(user.id, user.email, "sid-1")).willReturn("new-access-token")
        given(jwtTokenProvider.createRefreshToken(user.id, "sid-1")).willReturn("new-refresh-token")

        val tokenPair = authService().refresh(refreshToken)

        assertThat(tokenPair.accessToken).isEqualTo("new-access-token")
        assertThat(tokenPair.refreshToken).isEqualTo("new-refresh-token")
        then(loginSessionStore).should().rotateRefreshToken("sid-1", "new-refresh-token")
    }

    @Test
    fun `logout - refresh token 이 null 이면 아무 동작도 하지 않는다`() {
        authService().logout(null)
        verifyNoInteractions(jwtTokenProvider, loginSessionStore)
    }

    @Test
    fun `logout - 유효하지 않은 refresh token 이면 세션 삭제를 하지 않는다`() {
        val refreshToken = "invalid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(false)

        authService().logout(refreshToken)

        then(jwtTokenProvider).should().isValidRefreshToken(refreshToken)
        verifyNoInteractions(loginSessionStore)
    }

    @Test
    fun `logout - 유효한 refresh token 이면 세션을 삭제한다`() {
        val refreshToken = "valid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")

        authService().logout(refreshToken)

        then(loginSessionStore).should().delete("sid-1")
    }

    private fun assertUnauthorized(block: () -> Unit) {
        val exception = assertThrows<ResponseStatusException> { block() }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(exception.reason).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다.")
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "tester@vlainter.com",
        password: String = "{bcrypt}hashed-password",
        name: String = "테스터",
        status: UserStatus = UserStatus.ACTIVE
    ): User {
        return User(
            id = id,
            email = email,
            password = password,
            name = name,
            status = status
        )
    }

    private fun anyStringMatcher(): String {
        org.mockito.ArgumentMatchers.anyString()
        return ""
    }

    private fun anyLongMatcher(): Long {
        org.mockito.ArgumentMatchers.anyLong()
        return 0L
    }
}
