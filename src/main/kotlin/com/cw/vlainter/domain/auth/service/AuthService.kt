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
import com.cw.vlainter.global.security.RefreshTokenRotationResult
import com.cw.vlainter.global.security.RedirectUriValidator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

/**
 * 인증 도메인 핵심 로직을 담당한다.
 *
 * 설계 원칙:
 * - 인증 상태의 최종 진실원천은 Redis 세션(LoginSessionStore)
 * - JWT는 세션 식별자(sid)를 운반하는 서명 토큰
 * - Access/Refresh 모두 쿠키로 전달하며 Refresh는 회전(rotating)한다.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val loginSessionStore: LoginSessionStore,
    private val authAccessAuditService: AuthAccessAuditService,
    private val redirectUriValidator: RedirectUriValidator,
    private val emailVerificationService: EmailVerificationService,
    private val userLifecycleEmailService: UserLifecycleEmailService
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val passwordComplexityRegex = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,100}$")
    private val refreshReuseGraceWindow = Duration.ofSeconds(5)

    /**
     * 로그인 처리.
     *
     * - 이메일/비밀번호 검증
     * - 계정 상태 검증(ACTIVE만 허용)
     * - 세션 ID 발급 후 Access/Refresh 발급
     * - Redis에 세션 상태와 Refresh 해시 저장
     */
    fun login(request: LoginRequest): LoginResult {
        val user = userRepository.findByEmail(request.email)
            .orElseThrow { unauthorizedException() }

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw unauthorizedException()
        }
        validateUserForLogin(user)
        val validatedRedirectUri = redirectUriValidator.validate(request.redirectUri)

        return issueLoginResult(user, validatedRedirectUri, AuthProviderType.EMAIL)
    }

    fun loginOrSignupWithEmail(email: String, nameHint: String?, redirectUri: String?): LoginResult {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 정보가 유효하지 않습니다.")
        }

        val existingUser = userRepository.findByEmail(normalizedEmail).orElse(null)
        val user = existingUser ?: run {
            val resolvedName = resolveSocialName(nameHint, normalizedEmail)
            try {
                val createdUser = userRepository.save(
                    User(
                        email = normalizedEmail,
                        password = passwordEncoder.encode(generateSocialRandomPassword()),
                        name = resolvedName,
                        status = UserStatus.ACTIVE,
                        role = UserRole.USER
                    )
                )
                userLifecycleEmailService.sendWelcomeEmail(
                    email = createdUser.email,
                    userName = createdUser.name,
                    signupChannel = "카카오"
                )
                logger.info("Auth social signup created new user userId={} email={}", createdUser.id, createdUser.email)
                createdUser
            } catch (_: DataIntegrityViolationException) {
                val reusedUser = userRepository.findByEmail(normalizedEmail)
                    .orElseThrow { ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.") }
                logger.info("Auth social login reused existing user after concurrent signup userId={} email={}", reusedUser.id, reusedUser.email)
                reusedUser
            }
        }

        if (existingUser != null) {
            logger.info("Auth social login matched existing user userId={} email={}", user.id, user.email)
        }

        validateUserForLogin(user)
        val validatedRedirectUri = redirectUriValidator.validate(redirectUri)
        return issueLoginResult(user, validatedRedirectUri, AuthProviderType.KAKAO)
    }

    private fun issueLoginResult(user: User, validatedRedirectUri: String?, authProvider: AuthProviderType): LoginResult {

        val sessionId = UUID.randomUUID().toString()
        val accessToken = jwtTokenProvider.createAccessToken(user.id, user.email, sessionId, user.role)
        val refreshToken = jwtTokenProvider.createRefreshToken(user.id, sessionId)
        loginSessionStore.create(sessionId, user.id, refreshToken)
        logger.info(
            "Auth session issued userId={} email={} role={} sidPrefix={}",
            user.id,
            user.email,
            user.role,
            sessionId.take(8)
        )

        return LoginResult(
            userId = user.id,
            email = user.email,
            name = user.name,
            role = user.role,
            accessToken = accessToken,
            refreshToken = refreshToken,
            redirectUri = validatedRedirectUri,
            sessionId = sessionId,
            authProvider = authProvider
        )
    }

    /**
     * 회원가입 처리.
     *
     * - 이메일 인증 코드 검증(1회성 소비)
     * - 중복 이메일 검증
     * - 비밀번호 정책 검증 후 사용자 생성
     */
    fun signup(request: SignupRequest): User {
        val normalizedEmail = request.email.trim().lowercase()
        val normalizedName = request.name.trim()
        val password = request.password

        if (normalizedName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름을 입력해 주세요.")
        }
        if (!passwordComplexityRegex.matches(password)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "비밀번호는 8~100자이며 대문자, 소문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다."
            )
        }

        emailVerificationService.consumeVerifiedEmail(normalizedEmail)

        if (userRepository.findByEmail(normalizedEmail).isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }

        val user = User(
            email = normalizedEmail,
            password = passwordEncoder.encode(password),
            name = normalizedName,
            status = UserStatus.ACTIVE,
            role = UserRole.USER
        )
        return try {
            val saved = userRepository.save(user)
            userLifecycleEmailService.sendWelcomeEmail(
                email = saved.email,
                userName = saved.name,
                signupChannel = "이메일"
            )
            saved
        } catch (_: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }
    }

    /**
     * Refresh Token 재발급 처리.
     *
     * - 토큰 서명/만료 검증
     * - sid + userId + refresh 해시 일치 여부를 Redis에서 검증
     * - 동일 sid로 새 토큰 발급 후 Refresh 해시를 교체
     */
    fun refresh(refreshToken: String): TokenPair {
        if (!jwtTokenProvider.isValidRefreshToken(refreshToken)) {
            throw unauthorizedException()
        }

        val userId = jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)
        val sessionId = jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)
        val user = userRepository.findById(userId).orElseThrow { unauthorizedException() }
        validateUserForLogin(user)

        val newAccessToken = jwtTokenProvider.createAccessToken(user.id, user.email, sessionId, user.role)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(user.id, sessionId)
        when (
            loginSessionStore.rotateRefreshTokenAtomically(
                sessionId = sessionId,
                userId = userId,
                currentRefreshToken = refreshToken,
                nextRefreshToken = newRefreshToken,
                graceWindow = refreshReuseGraceWindow
            )
        ) {
            RefreshTokenRotationResult.ROTATED -> Unit
            RefreshTokenRotationResult.PREVIOUS_TOKEN_WITHIN_GRACE -> throw refreshUnauthorizedException()
            RefreshTokenRotationResult.HASH_MISMATCH -> {
                loginSessionStore.delete(sessionId)
                throw refreshUnauthorizedException()
            }
            RefreshTokenRotationResult.SESSION_NOT_FOUND -> throw refreshUnauthorizedException()
        }

        return TokenPair(newAccessToken, newRefreshToken)
    }

    /**
     * 로그아웃 처리.
     *
     * Refresh Token에서 sid를 추출해 Redis 세션을 삭제한다.
     * Access Token은 짧은 수명을 가지며, 세션 삭제 이후 필터 단계에서 차단된다.
     */
    fun logout(refreshToken: String?) {
        logoutByTokens(accessToken = null, refreshToken = refreshToken)
    }

    fun logoutByTokens(accessToken: String?, refreshToken: String?) {
        val refreshSessionId = refreshToken
            ?.takeIf { jwtTokenProvider.isValidRefreshToken(it) }
            ?.let { jwtTokenProvider.extractSessionIdFromRefreshToken(it) }
        if (!refreshSessionId.isNullOrBlank()) {
            try {
                authAccessAuditService.markLogout(refreshSessionId)
            } catch (ex: Exception) {
                logger.warn("로그아웃 감사 로그 기록에 실패했습니다. sidPrefix={}", refreshSessionId.take(8), ex)
            } finally {
                loginSessionStore.delete(refreshSessionId)
            }
            return
        }

        val accessSessionId = accessToken
            ?.let { jwtTokenProvider.extractSessionIdFromAccessTokenAllowExpired(it) }
        if (!accessSessionId.isNullOrBlank()) {
            try {
                authAccessAuditService.markLogout(accessSessionId)
            } catch (ex: Exception) {
                logger.warn("로그아웃 감사 로그 기록에 실패했습니다. sidPrefix={}", accessSessionId.take(8), ex)
            } finally {
                loginSessionStore.delete(accessSessionId)
            }
        }
    }

    /**
     * 로그인 가능한 계정 상태인지 검증한다.
     */
    private fun validateUserForLogin(user: User) {
        when (user.status) {
            UserStatus.ACTIVE -> return
            UserStatus.BLOCKED -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다. 관리자에게 문의해 주세요.")
            UserStatus.DELETED -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "삭제 처리된 계정입니다. 관리자에게 문의해 주세요.")
        }
    }

    /**
     * 인증 실패 공통 예외.
     *
     * 계정 존재 여부를 노출하지 않기 위해 동일 메시지로 응답한다.
     */
    private fun unauthorizedException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
    }

    private fun refreshUnauthorizedException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다. 다시 로그인해 주세요.")
    }

    private fun resolveSocialName(nameHint: String?, email: String): String {
        val trimmedName = nameHint?.trim().orEmpty()
        if (trimmedName.isNotBlank()) {
            return trimmedName.take(100)
        }
        val localPart = email.substringBefore("@").ifBlank { "Vlainter User" }
        return localPart.take(100)
    }

    private fun generateSocialRandomPassword(): String {
        return "Ka${UUID.randomUUID()}!1a"
    }
}

/**
 * 로그인 성공 시 컨트롤러로 전달되는 내부 결과 객체.
 */
data class LoginResult(
    val userId: Long,
    val email: String,
    val name: String,
    val role: UserRole,
    val accessToken: String,
    val refreshToken: String,
    val redirectUri: String?,
    val sessionId: String,
    val authProvider: AuthProviderType
)

/**
 * Refresh 성공 시 새 토큰 쌍을 전달한다.
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)
