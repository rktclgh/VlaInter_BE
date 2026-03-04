package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PasswordRecoveryServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var mailSender: JavaMailSender

    @Test
    fun sendTemporaryPasswordUpdatesPasswordAndSendsEmail() {
        val user = createUser()
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.of(user))
        given(passwordEncoder.encode(any(String::class.java))).willReturn("encoded-temp-password")
        given(userRepository.save(user)).willReturn(user)

        service().sendTemporaryPassword(" USER@vlainter.com ", "User Name")

        val encodedSourceCaptor = ArgumentCaptor.forClass(String::class.java)
        then(passwordEncoder).should().encode(encodedSourceCaptor.capture())
        val rawTemporaryPassword = encodedSourceCaptor.value
        assertThat(rawTemporaryPassword).hasSize(12)
        assertThat(rawTemporaryPassword.any { it.isDigit() }).isTrue()
        assertThat(rawTemporaryPassword.any { it.isLetter() }).isTrue()
        assertThat(user.password).isEqualTo("encoded-temp-password")

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        then(mailSender).should().send(messageCaptor.capture())
        val sentMessage = messageCaptor.value
        assertThat(sentMessage.to).containsExactly("user@vlainter.com")
        assertThat(sentMessage.from).isEqualTo("mailer@vlainter.com")
        assertThat(sentMessage.text).contains(rawTemporaryPassword)
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
    fun sendTemporaryPasswordThrowsServiceUnavailableWhenMailFails() {
        val user = createUser()
        given(userRepository.findByEmail("user@vlainter.com")).willReturn(Optional.of(user))
        given(passwordEncoder.encode(any(String::class.java))).willReturn("encoded-temp-password")
        given(userRepository.save(user)).willReturn(user)
        willThrow(MailSendException("smtp failed"))
            .given(mailSender)
            .send(any(SimpleMailMessage::class.java))

        val exception = assertThrows<ResponseStatusException> {
            service().sendTemporaryPassword("user@vlainter.com", "User Name")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    private fun service(): PasswordRecoveryService {
        return PasswordRecoveryService(
            userRepository = userRepository,
            passwordEncoder = passwordEncoder,
            mailSender = mailSender,
            senderEmail = "mailer@vlainter.com"
        )
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "user@vlainter.com",
        password: String = "encoded-password",
        name: String = "User Name",
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
}
