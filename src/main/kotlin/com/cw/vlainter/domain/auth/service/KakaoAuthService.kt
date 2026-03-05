package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.global.config.properties.KakaoProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException

@Service
class KakaoAuthService(
    private val restClientBuilder: RestClient.Builder,
    private val kakaoProperties: KakaoProperties,
    private val authService: AuthService
) {
    private val logger = LoggerFactory.getLogger(KakaoAuthService::class.java)
    private val restClient: RestClient = restClientBuilder.build()

    fun loginOrSignupWithKakao(code: String, redirectUri: String?, clientIdFromClient: String?): LoginResult {
        val clientId = resolveClientId(clientIdFromClient)
        val effectiveRedirectUri = resolveRedirectUri(redirectUri)
        val accessToken = requestAccessToken(clientId, effectiveRedirectUri, code)
        val userInfo = requestUserInfo(accessToken)

        val kakaoEmail = userInfo.kakaoAccount?.email?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카카오 이메일 동의가 필요합니다.")
        val profileName = userInfo.kakaoAccount.profile.nickname

        return authService.loginOrSignupWithEmail(kakaoEmail, profileName, null)
    }

    private fun resolveClientId(clientIdFromClient: String?): String {
        val fromClient = clientIdFromClient?.trim().orEmpty()
        if (fromClient.isNotEmpty()) return fromClient

        val configured = kakaoProperties.clientId.trim()
        if (configured.isNotEmpty()) return configured

        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "카카오 클라이언트 설정이 누락되었습니다.")
    }

    private fun resolveRedirectUri(redirectUri: String?): String {
        val fromRequest = redirectUri?.trim().orEmpty()
        if (fromRequest.isNotEmpty()) return fromRequest

        val configured = kakaoProperties.redirectUri.trim()
        if (configured.isNotEmpty()) return configured

        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카카오 redirectUri가 필요합니다.")
    }

    private fun requestAccessToken(clientId: String, redirectUri: String, code: String): String {
        val body: MultiValueMap<String, String> = LinkedMultiValueMap()
        body.add("grant_type", "authorization_code")
        body.add("client_id", clientId)
        if (kakaoProperties.clientSecret.isNotBlank()) {
            body.add("client_secret", kakaoProperties.clientSecret)
        }
        body.add("redirect_uri", redirectUri)
        body.add("code", code)

        val tokenResponse = runCatching {
            restClient.post()
                .uri(kakaoProperties.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(KakaoTokenResponse::class.java)
        }.getOrElse { ex ->
            if (ex is RestClientResponseException) {
                logger.warn(
                    "Kakao token exchange failed status={} body={} redirectUri={} hasClientSecret={}",
                    ex.statusCode.value(),
                    ex.responseBodyAsString.limitForLog(),
                    redirectUri,
                    kakaoProperties.clientSecret.isNotBlank()
                )
                throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "카카오 토큰 발급에 실패했습니다.(${ex.statusCode.value()})"
                )
            }
            logger.warn("Kakao token exchange failed exception={}", ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 발급에 실패했습니다.")
        }

        return tokenResponse?.accessToken
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 토큰 응답이 올바르지 않습니다.")
    }

    private fun requestUserInfo(accessToken: String): KakaoUserInfoResponse {
        return runCatching {
            restClient.get()
                .uri(kakaoProperties.userInfoUri)
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .body(KakaoUserInfoResponse::class.java)
        }.getOrElse { ex ->
            if (ex is RestClientResponseException) {
                logger.warn(
                    "Kakao user info failed status={} body={}",
                    ex.statusCode.value(),
                    ex.responseBodyAsString.limitForLog()
                )
            } else {
                logger.warn("Kakao user info failed exception={}", ex.message)
            }
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보 조회에 실패했습니다.")
        } ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "카카오 사용자 정보 응답이 비어 있습니다.")
    }
}

private fun String?.limitForLog(maxLength: Int = 300): String {
    if (this == null) return ""
    return if (this.length <= maxLength) this else this.substring(0, maxLength) + "..."
}

data class KakaoTokenResponse(
    @JsonProperty("access_token")
    val accessToken: String? = null
)

data class KakaoUserInfoResponse(
    val id: Long? = null,
    @JsonProperty("kakao_account")
    val kakaoAccount: KakaoAccount? = null
)

data class KakaoAccount(
    val email: String? = null,
    val profile: KakaoProfile = KakaoProfile()
)

data class KakaoProfile(
    val nickname: String? = null
)
