package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.config.properties.EmailVerificationProperties
import com.cw.vlainter.global.mail.EmailTemplateService
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
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
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.Optional
import java.util.Properties

@ExtendWith(MockitoExtension::class)
class EmailVerificationServiceTests {

    @Mock
    private lateinit var mailSender: JavaMailSender

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    @Mock
    private lateinit var emailTemplateService: EmailTemplateService

    private val properties = EmailVerificationProperties(
        codeExpSeconds = 300,
        resendCooldownSeconds = 60,
        codeLength = 6
    )

    @Test
    fun sendVerificationCodeStoresCodeAndCooldownAndSendsMail() {
        val rawEmail = "  SongChiH@iCloud.com  "
        val normalizedEmail = "songchih@icloud.com"
        val mimeMessage = MimeMessage(Session.getInstance(Properties()))
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(userRepository.findByEmail(normalizedEmail)).willReturn(Optional.empty())
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$normalizedEmail")).willReturn(false)
        given(mailSender.createMimeMessage()).willReturn(mimeMessage)
        given(emailTemplateService.buildVerificationCodeEmail(anyString(), eq(300L)))
            .willReturn("<html>verification-code-template</html>")

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

        then(mailSender).should().send(mimeMessage)
        assertThat(mimeMessage.subject).contains("VlaInter")
        assertThat(mimeMessage.allRecipients.map { it.toString() }).containsExactly(normalizedEmail)
        assertThat(mimeMessage.from).isNotNull
        assertThat(mimeMessage.from.map { it.toString() }).containsExactly("mailer@vlainter.com")
        assertThat(mimeMessage.content.toString()).contains("verification-code-template")
    }

    @Test
    fun invalidEmailThrowsBadRequest() {
        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode("invalid-email")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).isNotBlank()
        then(userRepository).should(never()).findByEmail(anyString())
        then(redisTemplate).should(never()).hasKey(any(String::class.java))
        verifyNoInteractions(valueOperations, mailSender, emailTemplateService)
    }

    @Test
    fun usedEmailThrowsConflictBeforeSendingCode() {
        val email = "songchih@icloud.com"
        given(userRepository.findByEmail(email)).willReturn(Optional.of(createUser(email)))

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(exception.reason).isEqualTo("이미 사용중인 이메일입니다.")
        then(redisTemplate).should(never()).hasKey(anyString())
        verifyNoInteractions(valueOperations, mailSender, emailTemplateService)
    }

    @Test
    fun requestDuringCooldownThrowsTooManyRequests() {
        val email = "songchih@icloud.com"
        given(userRepository.findByEmail(email)).willReturn(Optional.empty())
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email")).willReturn(true)

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exception.reason).isNotBlank()
        verifyNoInteractions(valueOperations, mailSender, emailTemplateService)
    }

    @Test
    fun secondRequestWithinOneMinuteCooldownThrowsTooManyRequests() {
        val email = "songchih@icloud.com"
        val firstMimeMessage = MimeMessage(Session.getInstance(Properties()))
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(userRepository.findByEmail(email)).willReturn(Optional.empty())
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email"))
            .willReturn(false, true)
        given(mailSender.createMimeMessage()).willReturn(firstMimeMessage)
        given(emailTemplateService.buildVerificationCodeEmail(anyString(), eq(300L)))
            .willReturn("<html>verification-code-template</html>")

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
        then(mailSender).should(times(1)).send(firstMimeMessage)
    }

    @Test
    fun smtpFailureRollsBackRedisKeysAndThrowsServiceUnavailable() {
        val email = "songchih@icloud.com"
        val mimeMessage = MimeMessage(Session.getInstance(Properties()))
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(userRepository.findByEmail(email)).willReturn(Optional.empty())
        given(redisTemplate.hasKey("auth:email-verification:cooldown:$email")).willReturn(false)
        given(mailSender.createMimeMessage()).willReturn(mimeMessage)
        given(emailTemplateService.buildVerificationCodeEmail(anyString(), eq(300L)))
            .willReturn("<html>verification-code-template</html>")
        willThrow(MailSendException("smtp failed"))
            .given(mailSender)
            .send(mimeMessage)

        val exception = assertThrows<ResponseStatusException> {
            service().sendVerificationCode(email)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exception.reason).isNotBlank()
        then(redisTemplate).should().delete("auth:email-verification:code:$email")
        then(redisTemplate).should().delete("auth:email-verification:cooldown:$email")
    }

    @Test
    fun verifyCodeSucceedsAndDeletesCodeAndCooldownKeys() {
        val rawEmail = " SongChiH@icloud.com "
        val normalizedEmail = "songchih@icloud.com"
        val inputCode = "123456"
        val keys = listOf(
            "auth:email-verification:code:$normalizedEmail",
            "auth:email-verification:cooldown:$normalizedEmail",
            "auth:email-verification:attempts:$normalizedEmail"
        )
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get("auth:email-verification:attempts:$normalizedEmail")).willReturn(null)
        given(redisTemplate.execute(any(DefaultRedisScript::class.java), eq(keys), anyString())).willReturn(1L)

        val result = service().verifyCode(rawEmail, inputCode)

        assertThat(result.verified).isTrue()
        then(redisTemplate).should().execute(any(DefaultRedisScript::class.java), eq(keys), eq(SHA256_123456))
    }

    @Test
    fun verifyCodeThrowsBadRequestWhenCodeKeyIsMissing() {
        val email = "songchih@icloud.com"
        val keys = listOf(
            "auth:email-verification:code:$email",
            "auth:email-verification:cooldown:$email",
            "auth:email-verification:attempts:$email"
        )
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get("auth:email-verification:attempts:$email")).willReturn(null)
        given(redisTemplate.execute(any(DefaultRedisScript::class.java), eq(keys), anyString())).willReturn(0L)
        given(valueOperations.increment("auth:email-verification:attempts:$email")).willReturn(1L)
        given(redisTemplate.getExpire("auth:email-verification:code:$email")).willReturn(120L)

        val exception = assertThrows<ResponseStatusException> {
            service().verifyCode(email, "123456")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(redisTemplate).should().expire("auth:email-verification:attempts:$email", Duration.ofSeconds(120))
    }

    @Test
    fun verifyCodeThrowsBadRequestWhenCodeDoesNotMatch() {
        val email = "songchih@icloud.com"
        val keys = listOf(
            "auth:email-verification:code:$email",
            "auth:email-verification:cooldown:$email",
            "auth:email-verification:attempts:$email"
        )
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get("auth:email-verification:attempts:$email")).willReturn(null)
        given(redisTemplate.execute(any(DefaultRedisScript::class.java), eq(keys), anyString())).willReturn(-1L)
        given(valueOperations.increment("auth:email-verification:attempts:$email")).willReturn(2L)
        given(redisTemplate.getExpire("auth:email-verification:code:$email")).willReturn(90L)

        val exception = assertThrows<ResponseStatusException> {
            service().verifyCode(email, "123456")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(redisTemplate).should().expire("auth:email-verification:attempts:$email", Duration.ofSeconds(90))
    }

    @Test
    fun verifyCodeThrowsTooManyRequestsWhenAttemptsExceeded() {
        val email = "songchih@icloud.com"
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.get("auth:email-verification:attempts:$email")).willReturn("5")

        val exception = assertThrows<ResponseStatusException> {
            service().verifyCode(email, "123456")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        then(valueOperations).should(never()).increment(anyString())
        then(redisTemplate).should(never()).expire(anyString(), any(Duration::class.java))
    }

    @Test
    fun verifyCodeThrowsBadRequestForInvalidCodeFormat() {
        val email = "songchih@icloud.com"

        val exception = assertThrows<ResponseStatusException> {
            service().verifyCode(email, "12ab")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        verifyNoInteractions(valueOperations)
        then(redisTemplate).should(never()).expire(anyString(), any(Duration::class.java))
    }

    private fun service(): EmailVerificationService {
        return EmailVerificationService(
            mailSender = mailSender,
            redisTemplate = redisTemplate,
            userRepository = userRepository,
            emailTemplateService = emailTemplateService,
            emailVerificationProperties = properties,
            senderEmail = "mailer@vlainter.com"
        )
    }

    private fun createUser(email: String): User {
        return User(
            id = 1L,
            email = email,
            password = "{bcrypt}hashed-password",
            name = "Tester"
        )
    }

    companion object {
        private const val SHA256_123456 = "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92"
    }
}
