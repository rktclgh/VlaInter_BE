package com.cw.vlainter.domain.payment.client

import com.cw.vlainter.global.config.properties.PortoneProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class PortoneApiClient(
    restTemplateBuilder: RestTemplateBuilder,
    private val portoneProperties: PortoneProperties
) : PortoneClient {
    private val restTemplate: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(3))
        .setReadTimeout(Duration.ofSeconds(5))
        .build()
    private val tokenLock = ReentrantLock()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtEpochSeconds: Long = 0

    override fun getPayment(impUid: String): PortonePayment {
        if (impUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 식별자(impUid)가 비어 있습니다.")
        }
        validateConfiguration()

        val headers = HttpHeaders()
        headers.accept = listOf(MediaType.APPLICATION_JSON)
        headers.setBearerAuth(getAccessToken())

        val entity = HttpEntity<Void>(headers)
        val url = "${portoneProperties.baseUrl.trim().trimEnd('/')}/payments/$impUid"
        val response = exchange(url, HttpMethod.GET, entity, PortoneEnvelope::class.java)
        return toPortonePayment(response.body)
    }

    override fun getPaymentByMerchantUid(merchantUid: String): PortonePayment {
        if (merchantUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 주문번호(merchantUid)가 비어 있습니다.")
        }
        validateConfiguration()

        val headers = HttpHeaders()
        headers.accept = listOf(MediaType.APPLICATION_JSON)
        headers.setBearerAuth(getAccessToken())

        val entity = HttpEntity<Void>(headers)
        val url = "${portoneProperties.baseUrl.trim().trimEnd('/')}/payments/find/$merchantUid"
        val response = exchange(url, HttpMethod.GET, entity, PortoneEnvelope::class.java)
        return toPortonePayment(response.body)
    }

    override fun cancelPayment(impUid: String, reason: String): PortonePayment {
        if (impUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불 결제 식별자(impUid)가 비어 있습니다.")
        }
        validateConfiguration()

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.accept = listOf(MediaType.APPLICATION_JSON)
        headers.setBearerAuth(getAccessToken())

        val payload = PortoneCancelRequest(
            impUid = impUid,
            reason = reason.ifBlank { "사용자 요청 환불" }
        )
        val entity = HttpEntity(payload, headers)
        val url = "${portoneProperties.baseUrl.trim().trimEnd('/')}/payments/cancel"
        val response = exchange(url, HttpMethod.POST, entity, PortoneEnvelope::class.java)
        return toPortonePayment(response.body)
    }

    private fun getAccessToken(): String {
        val now = Instant.now().epochSecond
        cachedToken?.let { token ->
            if (tokenExpiresAtEpochSeconds - 30 > now) {
                return token
            }
        }

        tokenLock.withLock {
            val refreshedNow = Instant.now().epochSecond
            cachedToken?.let { token ->
                if (tokenExpiresAtEpochSeconds - 30 > refreshedNow) {
                    return token
                }
            }

            val token = requestAccessToken()
            cachedToken = token.accessToken
            tokenExpiresAtEpochSeconds = token.expiredAt ?: (refreshedNow + 60)
            return token.accessToken
        }
    }

    private fun requestAccessToken(): PortoneTokenPayload {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.accept = listOf(MediaType.APPLICATION_JSON)
        val body = PortoneTokenRequest(
            impKey = portoneProperties.apiKey.trim(),
            impSecret = portoneProperties.apiSecret.trim()
        )
        val entity = HttpEntity(body, headers)
        val url = "${portoneProperties.baseUrl.trim().trimEnd('/')}/users/getToken"
        val response = exchange(url, HttpMethod.POST, entity, PortoneTokenEnvelope::class.java)
        val payload = response.body
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "포트원 토큰 응답이 비어 있습니다.")

        if (payload.code != 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "포트원 토큰 발급에 실패했습니다: ${payload.message ?: "unknown"}")
        }
        val tokenPayload = payload.response
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "포트원 토큰 정보가 누락되었습니다.")
        if (tokenPayload.accessToken.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "포트원 액세스 토큰이 비어 있습니다.")
        }
        return tokenPayload
    }

    private fun validateConfiguration() {
        if (portoneProperties.baseUrl.isBlank() || portoneProperties.apiKey.isBlank() || portoneProperties.apiSecret.isBlank()) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PortOne 설정이 누락되었습니다.")
        }
    }

    private fun toPortonePayment(body: PortoneEnvelope?): PortonePayment {
        val safeBody = body ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 조회 응답이 비어 있습니다.")
        if (safeBody.code != 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 조회에 실패했습니다: ${safeBody.message ?: "unknown"}")
        }
        val payload = safeBody.response
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 상세 정보가 누락되었습니다.")

        val amount = payload.amount ?: BigDecimal.ZERO
        return PortonePayment(
            impUid = payload.impUid.orEmpty(),
            merchantUid = payload.merchantUid.orEmpty(),
            status = payload.status.orEmpty(),
            amount = amount
        )
    }

    private fun <T> exchange(
        url: String,
        method: HttpMethod,
        entity: HttpEntity<*>,
        responseType: Class<T>
    ): ResponseEntity<T> {
        return runCatching {
            restTemplate.exchange(url, method, entity, responseType)
        }.getOrElse { ex ->
            val responseException = ex as? RestClientResponseException
            if (responseException != null) {
                val statusValue = responseException.statusCode.value()
                if (statusValue == HttpStatus.NOT_FOUND.value() && url.contains("/payments/")) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "결제 정보를 찾을 수 없습니다. 결제 직후라면 2~3초 후 다시 시도해 주세요."
                    )
                }
                val body = responseException.responseBodyAsString.orEmpty().replace("\n", " ").trim()
                val safeBody = if (body.length > 300) body.take(300) else body
                throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "PortOne 통신에 실패했습니다: HTTP $statusValue ${safeBody.ifBlank { "" }}".trim()
                )
            }

            val reason = (ex as? RestClientException)?.mostSpecificCause?.message ?: ex.message
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "PortOne 통신에 실패했습니다: $reason")
        }
    }
}

private data class PortoneTokenRequest(
    @JsonProperty("imp_key")
    val impKey: String,
    @JsonProperty("imp_secret")
    val impSecret: String
)

private data class PortoneCancelRequest(
    @JsonProperty("imp_uid")
    val impUid: String,
    val reason: String
)

private data class PortoneTokenEnvelope(
    val code: Int = -1,
    val message: String? = null,
    val response: PortoneTokenPayload? = null
)

private data class PortoneTokenPayload(
    @JsonProperty("access_token")
    val accessToken: String = "",
    @JsonProperty("expired_at")
    val expiredAt: Long? = null
)

private data class PortoneEnvelope(
    val code: Int = -1,
    val message: String? = null,
    val response: PortonePaymentPayload? = null
)

private data class PortonePaymentPayload(
    @JsonProperty("imp_uid")
    val impUid: String? = null,
    @JsonProperty("merchant_uid")
    val merchantUid: String? = null,
    val status: String? = null,
    val amount: BigDecimal? = null
)
