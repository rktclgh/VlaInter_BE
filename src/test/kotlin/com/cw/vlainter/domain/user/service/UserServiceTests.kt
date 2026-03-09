package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.user.dto.UpdateMemberByAdminRequest
import com.cw.vlainter.domain.user.dto.UpdateMyProfileRequest
import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class UserServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var userFileRepository: UserFileRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var loginSessionStore: LoginSessionStore

    @Mock
    private lateinit var userGeminiApiKeyService: UserGeminiApiKeyService

    @Test
    fun updateMyProfileUpdatesName() {
        val user = createUser()
        val principal = createPrincipal(user)
        val request = UpdateMyProfileRequest(name = "Updated Name")

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(userRepository.save(user)).willReturn(user)

        val response = userService().updateMyProfile(principal, request)

        assertThat(response.name).isEqualTo("Updated Name")
        assertThat(user.name).isEqualTo("Updated Name")
    }

    @Test
    fun updateMyProfileRejectsWrongCurrentPassword() {
        val user = createUser(password = "encoded-password")
        val principal = createPrincipal(user)
        val request = UpdateMyProfileRequest(
            currentPassword = "wrong-password",
            newPassword = "New-password-123!"
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(passwordEncoder.matches("wrong-password", "encoded-password")).willReturn(false)

        val exception = assertThrows<ResponseStatusException> {
            userService().updateMyProfile(principal, request)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(userRepository).should(never()).save(user)
    }

    @Test
    fun softDeleteMyAccountMarksUserDeletedAndRemovesSession() {
        val user = createUser()
        val principal = createPrincipal(user)

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(userRepository.save(user)).willReturn(user)

        userService().softDeleteMyAccount(principal)

        assertThat(user.status).isEqualTo(UserStatus.DELETED)
        then(userRepository).should().save(user)
        then(loginSessionStore).should().delete(principal.sessionId)
    }

    @Test
    fun changeMyPasswordUpdatesPassword() {
        val user = createUser(password = "encoded-old-password")
        val principal = createPrincipal(user)
        val request = ChangeMyPasswordRequest(
            currentPassword = "old-password",
            newPassword = "New-password-123!"
        )
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(passwordEncoder.matches("old-password", "encoded-old-password")).willReturn(true)
        given(passwordEncoder.encode("New-password-123!")).willReturn("encoded-new-password")
        given(userRepository.save(user)).willReturn(user)

        userService().changeMyPassword(principal, request)

        assertThat(user.password).isEqualTo("encoded-new-password")
        then(userRepository).should().save(user)
    }

    @Test
    fun changeMyPasswordRejectsWrongCurrentPassword() {
        val user = createUser(password = "encoded-old-password")
        val principal = createPrincipal(user)
        val request = ChangeMyPasswordRequest(
            currentPassword = "wrong-password",
            newPassword = "New-password-123!"
        )
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(passwordEncoder.matches("wrong-password", "encoded-old-password")).willReturn(false)

        val exception = assertThrows<ResponseStatusException> {
            userService().changeMyPassword(principal, request)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(userRepository).should(never()).save(user)
    }

    @Test
    fun changeMyPasswordRejectsSamePassword() {
        val user = createUser(password = "encoded-password")
        val principal = createPrincipal(user)
        val request = ChangeMyPasswordRequest(
            currentPassword = "same-password",
            newPassword = "same-password"
        )
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(passwordEncoder.matches("same-password", "encoded-password")).willReturn(true)

        val exception = assertThrows<ResponseStatusException> {
            userService().changeMyPassword(principal, request)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(userRepository).should(never()).save(user)
    }

    @Test
    fun getMembersByAdminRejectsNonAdminRole() {
        val user = createUser(role = UserRole.USER)
        val principal = createPrincipal(user)

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))

        val exception = assertThrows<ResponseStatusException> {
            userService().getMembersByAdmin(principal, page = 0, size = 20)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        then(userRepository).should(never()).findAll(any(Pageable::class.java))
    }

    @Test
    fun getMembersByAdminReturnsMemberList() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val principal = createPrincipal(adminUser)
        val member1 = createUser(id = 200L, email = "member1@vlainter.com")
        val member2 = createUser(id = 300L, email = "member2@vlainter.com")

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))
        given(userRepository.findAll(any(Pageable::class.java)))
            .willReturn(PageImpl(listOf(member2, member1), PageRequest.of(0, 2), 17L))

        val response = userService().getMembersByAdmin(principal, page = 0, size = 2)

        assertThat(response.totalCount).isEqualTo(17)
        assertThat(response.members.map { it.memberId }).containsExactly(300L, 200L)
    }

    @Test
    fun updateMemberByAdminUpdatesStatusAndRole() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val principal = createPrincipal(adminUser)
        val targetUser = createUser(id = 200L, email = "target@vlainter.com")
        val request = UpdateMemberByAdminRequest(
            name = "Blocked User",
            status = UserStatus.BLOCKED,
            role = UserRole.ADMIN
        )

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))
        given(userRepository.findById(targetUser.id)).willReturn(Optional.of(targetUser))
        given(userRepository.save(targetUser)).willReturn(targetUser)

        val response = userService().updateMemberByAdmin(principal, targetUser.id, request)

        assertThat(response.name).isEqualTo("Blocked User")
        assertThat(response.status).isEqualTo(UserStatus.BLOCKED)
        assertThat(response.role).isEqualTo(UserRole.ADMIN)
    }

    @Test
    fun updateMemberByAdminRejectsEmptyPatchRequest() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val principal = createPrincipal(adminUser)
        val targetUser = createUser(id = 200L, email = "target@vlainter.com")

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))
        given(userRepository.findById(targetUser.id)).willReturn(Optional.of(targetUser))

        val exception = assertThrows<ResponseStatusException> {
            userService().updateMemberByAdmin(principal, targetUser.id, UpdateMemberByAdminRequest())
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(userRepository).should(never()).save(any(User::class.java))
    }

    @Test
    fun hardDeleteMemberByAdminRejectsNonAdmin() {
        val user = createUser()
        val principal = createPrincipal(user)

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))

        val exception = assertThrows<ResponseStatusException> {
            userService().hardDeleteMemberByAdmin(principal, 999L)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verifyNoInteractions(userFileRepository)
    }

    @Test
    fun hardDeleteMemberByAdminRejectsSelfDelete() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val principal = createPrincipal(adminUser)

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))

        val exception = assertThrows<ResponseStatusException> {
            userService().hardDeleteMemberByAdmin(principal, adminUser.id)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(userRepository).should(never()).delete(any(User::class.java))
    }

    @Test
    fun hardDeleteMemberByAdminDeletesUserAndFiles() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val targetUser = createUser(id = 200L, email = "target@vlainter.com")
        val principal = createPrincipal(adminUser)

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))
        given(userRepository.findById(targetUser.id)).willReturn(Optional.of(targetUser))

        userService().hardDeleteMemberByAdmin(principal, targetUser.id)

        then(userFileRepository).should().deleteAllByUser_Id(targetUser.id)
        then(userRepository).should().delete(targetUser)
    }

    @Test
    fun hardDeleteMemberByAdminThrowsNotFoundWhenTargetMissing() {
        val adminUser = createUser(id = 100L, email = "admin@vlainter.com", role = UserRole.ADMIN)
        val principal = createPrincipal(adminUser)

        given(userRepository.findById(adminUser.id)).willReturn(Optional.of(adminUser))
        given(userRepository.findById(404L)).willReturn(Optional.empty())

        val exception = assertThrows<ResponseStatusException> {
            userService().hardDeleteMemberByAdmin(principal, 404L)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        then(userRepository).should(never()).delete(any(User::class.java))
    }

    private fun userService(): UserService {
        return UserService(
            userRepository = userRepository,
            userFileRepository = userFileRepository,
            passwordEncoder = passwordEncoder,
            loginSessionStore = loginSessionStore,
            userGeminiApiKeyService = userGeminiApiKeyService
        )
    }

    private fun createPrincipal(user: User): AuthPrincipal {
        return AuthPrincipal(
            userId = user.id,
            email = user.email,
            sessionId = "session-${user.id}",
            role = user.role
        )
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
