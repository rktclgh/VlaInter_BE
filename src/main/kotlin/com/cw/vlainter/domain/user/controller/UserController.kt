package com.cw.vlainter.domain.user.controller

import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.user.dto.UpdateMyAcademicProfileRequest
import com.cw.vlainter.domain.user.dto.UpdateGeminiApiKeyRequest
import com.cw.vlainter.domain.user.dto.UpdateMyProfileRequest
import com.cw.vlainter.domain.user.dto.UpdateMyServiceModeRequest
import com.cw.vlainter.domain.user.dto.UserProfileResponse
import com.cw.vlainter.domain.user.service.UserService
import com.cw.vlainter.global.security.AuthCookieManager
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletResponse

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val authCookieManager: AuthCookieManager,
    private val loginSessionStore: LoginSessionStore
) {
    @GetMapping("/me")
    fun getMyProfile(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.getMyProfile(principal))
    }

    @PatchMapping("/me")
    fun updateMyProfile(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestBody request: UpdateMyProfileRequest
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.updateMyProfile(principal, request))
    }

    @PatchMapping("/me/service-mode")
    fun updateMyServiceMode(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: UpdateMyServiceModeRequest
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.updateMyServiceMode(principal, request))
    }

    @PatchMapping("/me/academic-profile")
    fun updateMyAcademicProfile(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: UpdateMyAcademicProfileRequest
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.updateMyAcademicProfile(principal, request))
    }

    @PutMapping("/me/gemini-api-key")
    fun updateMyGeminiApiKey(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: UpdateGeminiApiKeyRequest
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.updateMyGeminiApiKey(principal, request))
    }

    @DeleteMapping("/me/gemini-api-key")
    fun clearMyGeminiApiKey(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<UserProfileResponse> {
        return ResponseEntity.ok(userService.clearMyGeminiApiKey(principal))
    }

    @PatchMapping("/me/password")
    fun changeMyPassword(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid
        @RequestBody request: ChangeMyPasswordRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, String>> {
        userService.changeMyPassword(principal, request)
        loginSessionStore.delete(principal.sessionId)
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearRefreshTokenCookie().toString())
        return ResponseEntity.ok(mapOf("message" to "비밀번호가 변경되었습니다."))
    }

    @DeleteMapping("/me")
    fun softDeleteMyAccount(
        @AuthenticationPrincipal principal: AuthPrincipal,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, String>> {
        userService.softDeleteMyAccount(principal)
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearRefreshTokenCookie().toString())

        return ResponseEntity.ok(mapOf("message" to "Account has been deactivated."))
    }
}
