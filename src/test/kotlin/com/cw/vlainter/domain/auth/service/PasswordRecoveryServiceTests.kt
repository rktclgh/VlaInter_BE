package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.mail.EmailTemplateService
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.Mockito.lenient
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.Properties
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class PasswordRecoveryServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var mailSender: JavaMailSender

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var redisValueOperations: ValueOperations<String, String>

    @Mock
    private lateinit var emailTemplateService: EmailTemplateService

    @Test
    fun sendTemporaryPasswordUpdatesPasswordAndSendsEmail() {
        val user = createUser()
        val mimeMessage = MimeMessage(Session.getInstance(Properties()))
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.of(user))
        allowRateLimitFor()
        given(passwordEncoder.encode(any(String::class.java))).willReturn("encoded-temp-password")
        given(userRepository.save(user)).willReturn(user)
        given(mailSender.createMimeMessage()).willReturn(mimeMessage)
        given(emailTemplateService.buildTemporaryPasswordEmail(anyString()))
            .willReturn("<html>temporary-password-template</html>")

        service().sendTemporaryPassword(" USER@vlainter.com ", "User Name")

        val encodedSourceCaptor = ArgumentCaptor.forClass(String::class.java)
        then(passwordEncoder).should().encode(encodedSourceCaptor.capture())
        val rawTemporaryPassword = encodedSourceCaptor.value
        assertThat(rawTemporaryPassword).hasSize(12)
        assertThat(rawTemporaryPassword.any { it.isDigit() }).isTrue()
        assertThat(rawTemporaryPassword.any { it.isLetter() }).isTrue()
        assertThat(user.password).isEqualTo("encoded-temp-password")

        then(mailSender).should().send(mimeMessage)
        assertThat(mimeMessage.subject).isNotBlank()
        assertThat(mimeMessage.allRecipients.map { it.toString() }).containsExactly("user@vlainter.com")
        assertThat(mimeMessage.from).isNotNull
        assertThat(mimeMessage.from.map { it.toString() }).containsExactly("mailer@vlainter.com")
    }

    @Test
    fun sendTemporaryPasswordThrowsBadRequestWhenNameDoesNotMatch() {
        val user = createUser(name = "Another Name")
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.of(user))

        val exception = assertThrows<ResponseStatusException> {
            service().sendTemporaryPassword("user@vlainter.com", "User Name")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun sendTemporaryPasswordThrowsBadRequestWhenUserDoesNotExist() {
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.empty())

        val exception = assertThrows<ResponseStatusException> {
            service().sendTemporaryPassword("user@vlainter.com", "User Name")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun sendTemporaryPasswordThrowsServiceUnavailableWhenMailFails() {
        val user = createUser()
        val mimeMessage = MimeMessage(Session.getInstance(Properties()))
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.of(user))
        allowRateLimitFor()
        given(passwordEncoder.encode(any(String::class.java))).willReturn("encoded-temp-password")
        given(userRepository.save(user)).willReturn(user)
        given(mailSender.createMimeMessage()).willReturn(mimeMessage)
        given(emailTemplateService.buildTemporaryPasswordEmail(anyString()))
            .willReturn("<html>temporary-password-template</html>")
        willThrow(MailSendException("smtp failed"))
            .given(mailSender)
            .send(mimeMessage)

        val exception = assertThrows<ResponseStatusException> {
            service().sendTemporaryPassword("user@vlainter.com", "User Name")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    private fun service(): PasswordRecoveryService {
        lenient().`when`(emailTemplateService.logoContentId()).thenReturn("vlainter-logo")
        lenient().`when`(emailTemplateService.logoResource()).thenReturn(ClassPathResource("email/logo/favicon.png"))
        return PasswordRecoveryService(
            userRepository = userRepository,
            passwordEncoder = passwordEncoder,
            mailSender = mailSender,
            redisTemplate = redisTemplate,
            emailTemplateService = emailTemplateService,
            senderEmail = "mailer@vlainter.com"
        )
    }

    private fun allowRateLimitFor() {
        given(redisTemplate.opsForValue()).willReturn(redisValueOperations)
        given(
            redisValueOperations.setIfAbsent(
                "auth:password-recovery:cooldown:user@vlainter.com",
                "1",
                Duration.ofMinutes(1)
            )
        ).willReturn(true)
        given(redisValueOperations.increment(anyString())).willReturn(1L)
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "user@vlainter.com",
        password: String = "encoded-password",
        name: String = "User Name",
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
}
