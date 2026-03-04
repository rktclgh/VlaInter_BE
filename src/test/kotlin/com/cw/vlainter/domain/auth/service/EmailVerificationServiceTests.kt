package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.global.config.properties.EmailVerificationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class EmailVerificationServiceTests {

    @Mock
    private lateinit var mailSender: JavaMailSender

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private val properties = EmailVerificationProperties(
        codeExpSeconds = 300,
        resendCooldownSeconds = 60,
        codeLength = 6
    )

    @Test
    fun sendVerificationCodeStoresCodeAndCooldownAndSendsMail() {
        val rawEmail = "  SongChiH@iCloud.com  "
        val normalizedEmail = "songchih@icloud.com"
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$normalizedEmail")).willReturn(false)

        val result = service().sendVerificationCode(rawEmail)

        assertThat(result.expiresInSeconds).isEqualTo(300)

        then(valueOperations).should().set(
            eq("auth:email-verification:code:$normalizedEmail"),
            argThat { it.matches(Regex("[0-9a-f]{64}")) },
            eq(Duration.ofSeconds(300))
        )
        then(valueOperations).should().set(
            eq("auth:email-verification:cooldown:$normalizedEmail"),
            eq("1"),
            eq(Duration.ofSeconds(60))
        )

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        then(mailSender).should().send(messageCaptor.capture())
        val sentMessage = messageCaptor.value
        assertThat(sentMessage.to).containsExactly(normalizedEmail)
        assertThat(sentMessage.from).isEqualTo("mailer@vlainter.com")
        assertThat(sentMessage.subject).contains("VlaInter")
        assertThat(sentMessage.text).contains("300")
    }

    @Test
    fun invalidEmailThrowsBadRequest() {
        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode("invalid-email")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).isNotBlank()
        then(redisTemplate).should(never()).hasKey(any(String::class.java))
        verifyNoInteractions(valueOperations, mailSender)
    }

    @Test
    fun requestDuringCooldownThrowsTooManyRequests() {
        val email = "songchih@icloud.com"
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email")).willReturn(true)

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exception.reason).isNotBlank()
        verifyNoInteractions(valueOperations, mailSender)
    }

    @Test
    fun secondRequestWithinOneMinuteCooldownThrowsTooManyRequests() {
        val email = "songchih@icloud.com"
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email"))
            .willReturn(false, true)

        service().sendVerificationCode(email)

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exception.reason).isNotBlank()
        then(valueOperations).should().set(
            eq("auth:email-verification:cooldown:$email"),
            eq("1"),
            eq(Duration.ofSeconds(60))
        )
        then(mailSender).should(times(1)).send(any(SimpleMailMessage::class.java))
    }

    @Test
    fun smtpFailureRollsBackRedisKeysAndThrowsServiceUnavailable() {
        val email = "songchih@icloud.com"
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email")).willReturn(false)
        willThrow(MailSendException("smtp failed"))
            .given(mailSender)
            .send(any(SimpleMailMessage::class.java))

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exception.reason).isNotBlank()
        then(redisTemplate).should().delete("auth:email-verification:code:$email")
        then(redisTemplate).should().delete("auth:email-verification:cooldown:$email")
    }

    private fun service(): EmailVerificationService {
        return EmailVerificationService(
            mailSender = mailSender,
            redisTemplate = redisTemplate,
            emailVerificationProperties = properties,
            senderEmail = "mailer@vlainter.com"
        )
    }
}
