package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.user.dto.AdminMemberDetailResponse
import com.cw.vlainter.domain.user.dto.AdminMemberListResponse
import com.cw.vlainter.domain.user.dto.AdminMemberSummaryResponse
import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.user.dto.UpdateMyProfileRequest
import com.cw.vlainter.domain.user.dto.UpdateMemberByAdminRequest
import com.cw.vlainter.domain.user.dto.UserProfileResponse
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userFileRepository: UserFileRepository,
    private val passwordEncoder: PasswordEncoder,
    private val loginSessionStore: LoginSessionStore
) {
    private val passwordComplexityRegex = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,100}$")

    @Transactional
    fun updateMyProfile(principal: AuthPrincipal, request: UpdateMyProfileRequest): UserProfileResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        ensureActiveUser(user.status)

        val hasNameUpdate = !request.name.isNullOrBlank()
        val hasPasswordUpdate = !request.currentPassword.isNullOrBlank() || !request.newPassword.isNullOrBlank()
        if (!hasNameUpdate && !hasPasswordUpdate) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "수정할 프로필 정보가 없습니다.")
        }

        if (hasNameUpdate) {
            val trimmedName = request.name!!.trim()
            validateNameLength(trimmedName)
            user.name = trimmedName
        }

        if (hasPasswordUpdate) {
            if (request.currentPassword.isNullOrBlank() || request.newPassword.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 비밀번호와 새 비밀번호를 모두 입력해 주세요.")
            }
            applyPasswordChange(user, request.currentPassword, request.newPassword)
        }

        val saved = userRepository.save(user)
        return toProfileResponse(saved.id, saved.email, saved.name, saved.status)
    }

    @Transactional
    fun changeMyPassword(principal: AuthPrincipal, request: ChangeMyPasswordRequest) {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        ensureActiveUser(user.status)
        applyPasswordChange(user, request.currentPassword, request.newPassword)
        userRepository.save(user)
    }

    @Transactional
    fun softDeleteMyAccount(principal: AuthPrincipal) {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        ensureActiveUser(user.status)

        user.status = UserStatus.DELETED
        userRepository.save(user)
        runAfterCommit {
            loginSessionStore.delete(principal.sessionId)
        }
    }

    @Transactional(readOnly = true)
    fun getMembersByAdmin(adminPrincipal: AuthPrincipal, page: Int, size: Int): AdminMemberListResponse {
        authorizeAdmin(adminPrincipal)
        validatePageRequest(page, size)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val memberPage = userRepository.findAll(pageable)
        val members = memberPage.content.map { toAdminMemberSummaryResponse(it) }
        return AdminMemberListResponse(
            totalCount = memberPage.totalElements.toInt(),
            members = members
        )
    }

    @Transactional(readOnly = true)
    fun getMemberByAdmin(adminPrincipal: AuthPrincipal, memberId: Long): AdminMemberDetailResponse {
        authorizeAdmin(adminPrincipal)
        val member = findUserOrNotFound(memberId)
        return toAdminMemberDetailResponse(member)
    }

    @Transactional
    fun updateMemberByAdmin(
        adminPrincipal: AuthPrincipal,
        memberId: Long,
        request: UpdateMemberByAdminRequest
    ): AdminMemberDetailResponse {
        authorizeAdmin(adminPrincipal)
        val member = findUserOrNotFound(memberId)

        var changed = false
        val normalizedName = request.name?.trim()
        if (normalizedName != null) {
            if (normalizedName.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 비어 있을 수 없습니다.")
            }
            if (normalizedName.length > 100) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 100자를 초과할 수 없습니다.")
            }
            member.name = normalizedName
            changed = true
        }

        if (request.status != null) {
            member.status = request.status
            changed = true
        }

        if (request.role != null) {
            member.role = request.role
            changed = true
        }

        if (!changed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "최소 하나 이상의 항목을 수정해야 합니다.")
        }

        val saved = userRepository.save(member)
        return toAdminMemberDetailResponse(saved)
    }

    @Transactional
    fun hardDeleteMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 완전 삭제할 수 없습니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)

        userFileRepository.deleteAllByUser_Id(targetUser.id)
        userRepository.delete(targetUser)
    }

    @Transactional(readOnly = true)
    fun getMyProfile(principal: AuthPrincipal): UserProfileResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        return toProfileResponse(user.id, user.email, user.name, user.status)
    }

    private fun ensureActiveUser(status: UserStatus) {
        if (status != UserStatus.ACTIVE) {
            throw unauthorizedException()
        }
    }

    private fun unauthorizedException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
    }

    private fun applyPasswordChange(user: User, currentPassword: String, newPassword: String) {
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다.")
        }
        if (!passwordComplexityRegex.matches(newPassword)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "새 비밀번호는 8~100자이며 대문자, 소문자, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다."
            )
        }
        if (currentPassword == newPassword) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "새 비밀번호는 현재 비밀번호와 달라야 합니다.")
        }
        user.password = passwordEncoder.encode(newPassword)
    }

    private fun validateNameLength(name: String) {
        if (name.length > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 100자를 초과할 수 없습니다.")
        }
    }

    private fun validatePageRequest(page: Int, size: Int) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size !in 1..100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상 100 이하여야 합니다.")
        }
    }

    private fun runAfterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                action()
            }
        })
    }

    private fun findUserOrNotFound(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.") }
    }

    private fun authorizeAdmin(principal: AuthPrincipal): User {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }

        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 계정은 관리자 API에 접근할 수 없습니다.")
        }
        if (user.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.")
        }
        return user
    }

    private fun toProfileResponse(
        userId: Long,
        email: String,
        name: String,
        status: UserStatus
    ): UserProfileResponse {
        return UserProfileResponse(
            userId = userId,
            email = email,
            name = name,
            status = status
        )
    }

    private fun toAdminMemberSummaryResponse(user: User): AdminMemberSummaryResponse {
        return AdminMemberSummaryResponse(
            memberId = user.id,
            email = user.email,
            name = user.name,
            status = user.status,
            role = user.role,
            createdAt = user.createdAt
        )
    }

    private fun toAdminMemberDetailResponse(user: User): AdminMemberDetailResponse {
        return AdminMemberDetailResponse(
            memberId = user.id,
            email = user.email,
            name = user.name,
            status = user.status,
            role = user.role,
            point = user.point,
            free = user.free,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }
}
