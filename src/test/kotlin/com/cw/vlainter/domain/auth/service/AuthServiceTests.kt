package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.auth.dto.LoginRequest
import com.cw.vlainter.domain.auth.dto.SignupRequest
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserLifecycleEmailService
import com.cw.vlainter.global.security.JwtTokenProvider
import com.cw.vlainter.global.security.LoginSessionStore
import com.cw.vlainter.global.security.RefreshTokenValidationResult
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
import java.time.Duration
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

    @Mock
    private lateinit var emailVerificationService: EmailVerificationService

    @Mock
    private lateinit var authAccessAuditService: AuthAccessAuditService

    @Mock
    private lateinit var userLifecycleEmailService: UserLifecycleEmailService

    private fun authService(): AuthService = AuthService(
        userRepository = userRepository,
        passwordEncoder = passwordEncoder,
        jwtTokenProvider = jwtTokenProvider,
        loginSessionStore = loginSessionStore,
        authAccessAuditService = authAccessAuditService,
        redirectUriValidator = redirectUriValidator,
        emailVerificationService = emailVerificationService,
        userLifecycleEmailService = userLifecycleEmailService
    )

    @Test
    fun `login issues tokens and creates session`() {
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
                anyStringMatcher(),
                eqUserRoleMatcher(user.role)
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
        assertThat(result.role).isEqualTo(user.role)
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.redirectUri).isEqualTo(request.redirectUri)
        assertThat(accessTokenSessionId).isNotBlank()
        assertThat(refreshTokenSessionId).isNotBlank()
        assertThat(accessTokenSessionId).isEqualTo(refreshTokenSessionId)

        then(jwtTokenProvider).should().createAccessToken(user.id, user.email, accessTokenSessionId!!, user.role)
        then(loginSessionStore).should().create(accessTokenSessionId!!, user.id, "refresh-token")
    }

    @Test
    fun `login fails when email does not exist`() {
        val request = LoginRequest(email = "missing@vlainter.com", password = "Password123!")
        given(userRepository.findByEmail(request.email)).willReturn(Optional.empty(), Optional.empty())

        assertUnauthorized { authService().login(request) }
        verifyNoInteractions(passwordEncoder, jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login fails when password does not match`() {
        val user = createUser()
        val request = LoginRequest(email = user.email, password = "WrongPassword!")

        given(userRepository.findByEmail(user.email)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(request.password, user.password)).willReturn(false)

        assertUnauthorized { authService().login(request) }
        verifyNoInteractions(jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login fails for inactive account`() {
        val blockedUser = createUser(status = UserStatus.BLOCKED)
        val request = LoginRequest(email = blockedUser.email, password = "Password123!")

        given(userRepository.findByEmail(blockedUser.email)).willReturn(Optional.of(blockedUser))
        given(passwordEncoder.matches(request.password, blockedUser.password)).willReturn(true)

        assertForbidden { authService().login(request) }
        verifyNoInteractions(jwtTokenProvider, loginSessionStore, redirectUriValidator)
    }

    @Test
    fun `login blocks disallowed redirect_uri before session creation`() {
        val user = createUser()
        val request = LoginRequest(
            email = user.email,
            password = "Password123!",
            redirectUri = "https://evil.example.com/callback"
        )

        given(userRepository.findByEmail(user.email)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(request.password, user.password)).willReturn(true)
        given(redirectUriValidator.validate(request.redirectUri))
            .willThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri is not allowed."))

        val exception = assertThrows<ResponseStatusException> { authService().login(request) }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).isEqualTo("redirect_uri is not allowed.")
        verifyNoInteractions(jwtTokenProvider, loginSessionStore)
    }

    @Test
    fun `refresh fails for invalid token`() {
        val refreshToken = "invalid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(false)

        assertUnauthorized { authService().refresh(refreshToken) }
        verifyNoInteractions(loginSessionStore, userRepository)
    }

    @Test
    fun `refresh does not delete session when previous token is retried within grace window`() {
        val refreshToken = "refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(1L)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5)))
            .willReturn(RefreshTokenValidationResult.PREVIOUS_TOKEN_WITHIN_GRACE)

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5))
        then(loginSessionStore).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `refresh deletes session when token hash mismatches outside grace window`() {
        val refreshToken = "refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(1L)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5)))
            .willReturn(RefreshTokenValidationResult.HASH_MISMATCH)

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5))
        then(loginSessionStore).should().delete("sid-1")
    }

    @Test
    fun `refresh fails when user is missing`() {
        val refreshToken = "refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(1L)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5)))
            .willReturn(RefreshTokenValidationResult.CURRENT_TOKEN)
        given(userRepository.findById(1L)).willReturn(Optional.empty())

        assertUnauthorized { authService().refresh(refreshToken) }
        then(loginSessionStore).should().inspectRefreshToken("sid-1", 1L, refreshToken, Duration.ofSeconds(5))
        then(loginSessionStore).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `refresh fails for inactive account`() {
        val refreshToken = "refresh-token"
        val blockedUser = createUser(status = UserStatus.BLOCKED)

        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(blockedUser.id)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.inspectRefreshToken("sid-1", blockedUser.id, refreshToken, Duration.ofSeconds(5)))
            .willReturn(RefreshTokenValidationResult.CURRENT_TOKEN)
        given(userRepository.findById(blockedUser.id)).willReturn(Optional.of(blockedUser))

        assertForbidden { authService().refresh(refreshToken) }
        then(loginSessionStore).should().inspectRefreshToken("sid-1", blockedUser.id, refreshToken, Duration.ofSeconds(5))
        then(loginSessionStore).shouldHaveNoMoreInteractions()
    }

    @Test
    fun `refresh issues new token pair and rotates refresh token`() {
        val refreshToken = "refresh-token"
        val user = createUser()

        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)).willReturn(user.id)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")
        given(loginSessionStore.inspectRefreshToken("sid-1", user.id, refreshToken, Duration.ofSeconds(5)))
            .willReturn(RefreshTokenValidationResult.CURRENT_TOKEN)
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(jwtTokenProvider.createAccessToken(user.id, user.email, "sid-1", user.role)).willReturn("new-access-token")
        given(jwtTokenProvider.createRefreshToken(user.id, "sid-1")).willReturn("new-refresh-token")

        val tokenPair = authService().refresh(refreshToken)

        assertThat(tokenPair.accessToken).isEqualTo("new-access-token")
        assertThat(tokenPair.refreshToken).isEqualTo("new-refresh-token")
        then(loginSessionStore).should().rotateRefreshToken("sid-1", "new-refresh-token")
    }

    @Test
    fun `signup sends welcome email for email signup`() {
        val request = SignupRequest(
            email = "new@vlainter.com",
            password = "Password123!",
            name = "New User"
        )
        val savedUser = createUser(email = request.email, name = request.name)

        given(userRepository.findByEmail(request.email)).willReturn(Optional.empty(), Optional.empty())
        given(passwordEncoder.encode(request.password)).willReturn("encoded-password")
        given(userRepository.save(org.mockito.ArgumentMatchers.any(User::class.java))).willReturn(savedUser)

        val result = authService().signup(request)

        assertThat(result.email).isEqualTo(request.email)
        then(emailVerificationService).should().consumeVerifiedEmail(request.email)
        then(userLifecycleEmailService).should().sendWelcomeEmail(savedUser.email, savedUser.name, "이메일")
    }

    @Test
    fun `social signup sends welcome email only for new user`() {
        given(userRepository.findByEmail("social@vlainter.com")).willReturn(Optional.empty())
        given(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).willReturn("encoded-social-password")
        given(userRepository.save(org.mockito.ArgumentMatchers.any(User::class.java)))
            .willReturn(createUser(email = "social@vlainter.com", name = "Social User"))
        given(redirectUriValidator.validate(null)).willReturn(null)
        given(
            jwtTokenProvider.createAccessToken(
                anyLongMatcher(),
                anyStringMatcher(),
                anyStringMatcher(),
                eqUserRoleMatcher(UserRole.USER)
            )
        ).willReturn("access-token")
        given(jwtTokenProvider.createRefreshToken(anyLongMatcher(), anyStringMatcher())).willReturn("refresh-token")

        val result = authService().loginOrSignupWithEmail("social@vlainter.com", "Social User", null)

        assertThat(result.email).isEqualTo("social@vlainter.com")
        then(userLifecycleEmailService).should().sendWelcomeEmail("social@vlainter.com", "Social User", "카카오")
    }

    @Test
    fun `logout does nothing when refresh token is null`() {
        authService().logout(null)
        verifyNoInteractions(jwtTokenProvider, loginSessionStore)
    }

    @Test
    fun `logout does not delete session for invalid refresh token`() {
        val refreshToken = "invalid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(false)

        authService().logout(refreshToken)

        then(jwtTokenProvider).should().isValidRefreshToken(refreshToken)
        verifyNoInteractions(loginSessionStore)
    }

    @Test
    fun `logout deletes session for valid refresh token`() {
        val refreshToken = "valid-refresh-token"
        given(jwtTokenProvider.isValidRefreshToken(refreshToken)).willReturn(true)
        given(jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)).willReturn("sid-1")

        authService().logout(refreshToken)

        then(loginSessionStore).should().delete("sid-1")
    }

    private fun assertUnauthorized(block: () -> Unit) {
        val exception = assertThrows<ResponseStatusException> { block() }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun assertForbidden(block: () -> Unit) {
        val exception = assertThrows<ResponseStatusException> { block() }
        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "tester@vlainter.com",
        password: String = "{bcrypt}hashed-password",
        name: String = "Tester",
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER
    ): User {
        return User(
            id = id,
            email = email,
            password = password,
            name = name,
            status = status,
            role = role
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

    private fun eqUserRoleMatcher(expectedRole: UserRole): UserRole {
        org.mockito.ArgumentMatchers.eq(expectedRole)
        return expectedRole
    }
}
