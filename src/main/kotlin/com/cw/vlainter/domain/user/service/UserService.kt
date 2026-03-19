package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.user.dto.AdminMemberDetailResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessDailyCountResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessGlobalDailyMetricResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessGlobalSummaryResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessLogResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessSummaryResponse
import com.cw.vlainter.domain.user.dto.AdminMemberListResponse
import com.cw.vlainter.domain.user.dto.AdminMemberSummaryResponse
import com.cw.vlainter.domain.user.dto.ChangeMyPasswordRequest
import com.cw.vlainter.domain.academic.service.AcademicSearchService
import com.cw.vlainter.domain.user.dto.UpdateMyAcademicProfileRequest
import com.cw.vlainter.domain.user.dto.UpdateGeminiApiKeyRequest
import com.cw.vlainter.domain.user.dto.UpdateMyProfileRequest
import com.cw.vlainter.domain.user.dto.UpdateMyServiceModeRequest
import com.cw.vlainter.domain.user.dto.UpdateMemberByAdminRequest
import com.cw.vlainter.domain.user.dto.UserProfileResponse
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserServiceMode
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.auth.service.AuthAccessAuditService
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.LoginSessionStore
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userFileRepository: UserFileRepository,
    private val passwordEncoder: PasswordEncoder,
    private val loginSessionStore: LoginSessionStore,
    private val userGeminiApiKeyService: UserGeminiApiKeyService,
    private val authAccessAuditService: AuthAccessAuditService,
    private val userLifecycleEmailService: UserLifecycleEmailService,
    private val academicSearchService: AcademicSearchService
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
        return toProfileResponse(saved)
    }

    @Transactional
    fun updateMyServiceMode(principal: AuthPrincipal, request: UpdateMyServiceModeRequest): UserProfileResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        ensureActiveUser(user.status)

        user.serviceMode = request.serviceMode
        val saved = userRepository.save(user)
        return toProfileResponse(saved)
    }

    @Transactional
    fun updateMyAcademicProfile(principal: AuthPrincipal, request: UpdateMyAcademicProfileRequest): UserProfileResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        ensureActiveUser(user.status)

        val normalizedUniversity = request.universityName.normalizeAcademicText("대학교")
        val normalizedDepartment = request.departmentName.normalizeAcademicText("학과")
        val universityId = request.universityId
        val departmentId = request.departmentId
        if (universityId != null && normalizedUniversity == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학교 이름 없이 대학교 ID만 보낼 수 없습니다.")
        }
        if (departmentId != null && normalizedDepartment == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "학과 이름 없이 학과 ID만 보낼 수 없습니다.")
        }

        if ((normalizedUniversity == null) != (normalizedDepartment == null)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학교와 학과는 함께 입력하거나 함께 비워 주세요.")
        }
        if (user.serviceMode == UserServiceMode.STUDENT && normalizedUniversity == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학생 모드에서는 대학교와 학과를 입력해 주세요.")
        }
        if (normalizedUniversity != null) {
            val resolvedUniversity = resolveAcademicInputOrThrow {
                academicSearchService.resolveOrCreateUniversity(
                    universityName = normalizedUniversity,
                    universityId = universityId
                )
            }
            user.universityName = resolvedUniversity.universityName
            val resolvedDepartment = resolveAcademicInputOrThrow {
                academicSearchService.resolveOrCreateDepartment(
                    university = resolvedUniversity,
                    departmentName = normalizedDepartment ?: "",
                    departmentId = departmentId
                )
            }
            user.departmentName = resolvedDepartment.departmentName
        } else {
            user.universityName = null
            user.departmentName = null
        }
        val saved = userRepository.save(user)
        return toProfileResponse(saved)
    }

    @Transactional
    fun updateMyGeminiApiKey(principal: AuthPrincipal, request: UpdateGeminiApiKeyRequest): UserProfileResponse {
        val saved = userGeminiApiKeyService.updateMyGeminiApiKey(principal, request.geminiApiKey)
        return toProfileResponse(saved)
    }

    @Transactional
    fun clearMyGeminiApiKey(principal: AuthPrincipal): UserProfileResponse {
        val saved = userGeminiApiKeyService.clearMyGeminiApiKey(principal)
        return toProfileResponse(saved)
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

        val originalEmail = user.email
        val originalName = user.name
        markUserSoftDeleted(user)
        userRepository.save(user)
        loginSessionStore.deleteAllByUserId(user.id)
        userLifecycleEmailService.sendAccountDeletionEmail(originalEmail, originalName)
    }

    @Transactional(readOnly = true)
    fun getMembersByAdmin(adminPrincipal: AuthPrincipal, page: Int, size: Int, keyword: String = ""): AdminMemberListResponse {
        authorizeAdmin(adminPrincipal)
        validatePageRequest(page, size)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val normalizedKeyword = keyword.trim()
        val memberPage = if (normalizedKeyword.isBlank()) {
            userRepository.findAll(pageable)
        } else {
            userRepository.searchMembers(normalizedKeyword, pageable)
        }
        val members = memberPage.content.map { toAdminMemberSummaryResponse(it) }
        return AdminMemberListResponse(
            totalCount = memberPage.totalElements.toInt(),
            members = members
        )
    }

    @Transactional
    fun getMemberByAdmin(
        adminPrincipal: AuthPrincipal,
        memberId: Long
    ): AdminMemberDetailResponse {
        authorizeAdmin(adminPrincipal)
        val member = findUserOrNotFound(memberId)
        return toAdminMemberDetailResponse(member, false)
    }

    @Transactional
    fun getGlobalAccessSummaryByAdmin(
        adminPrincipal: AuthPrincipal,
        windowDays: Int
    ): AdminMemberAccessGlobalSummaryResponse {
        return buildGlobalAccessSummaryByAdmin(adminPrincipal, windowDays, false)
    }

    @Transactional
    fun refreshGlobalAccessSummaryByAdmin(
        adminPrincipal: AuthPrincipal,
        windowDays: Int
    ): AdminMemberAccessGlobalSummaryResponse {
        return buildGlobalAccessSummaryByAdmin(adminPrincipal, windowDays, true)
    }

    @Transactional
    fun refreshMemberAccessByAdmin(adminPrincipal: AuthPrincipal, memberId: Long): AdminMemberDetailResponse {
        authorizeAdmin(adminPrincipal)
        val member = findUserOrNotFound(memberId)
        return toAdminMemberDetailResponse(member, true)
    }

    private fun buildGlobalAccessSummaryByAdmin(
        adminPrincipal: AuthPrincipal,
        windowDays: Int,
        refresh: Boolean
    ): AdminMemberAccessGlobalSummaryResponse {
        authorizeAdmin(adminPrincipal)
        val normalizedWindowDays = when (windowDays) {
            7, 30 -> windowDays
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "windowDays는 7 또는 30만 지원합니다.")
        }
        val summary = authAccessAuditService.getGlobalSummary(normalizedWindowDays, refresh)
        return AdminMemberAccessGlobalSummaryResponse(
            windowDays = summary.windowDays,
            totalMemberCount = summary.totalMemberCount,
            totalLoginCount = summary.totalLoginCount,
            totalActionCount = summary.totalActionCount,
            averageLoginCount = summary.averageLoginCount,
            averageActionCount = summary.averageActionCount,
            averageSessionMinutes = summary.averageSessionMinutes,
            averageActiveSessionCount = summary.averageActiveSessionCount,
            calculatedAt = summary.calculatedAt,
            dailyMetrics = summary.dailyMetrics.map {
                AdminMemberAccessGlobalDailyMetricResponse(
                    date = it.date,
                    averageLoginCount = it.averageLoginCount,
                    averageActionCount = it.averageActionCount,
                    averageSessionMinutes = it.averageSessionMinutes
                )
            }
        )
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
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "회원 상태 변경은 전용 비활성화/활성화 API를 사용해 주세요.")
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
    fun blockMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 비활성화할 수 없습니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)
        if (targetUser.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 삭제 처리된 회원입니다.")
        }

        targetUser.status = UserStatus.BLOCKED
        userRepository.save(targetUser)
        loginSessionStore.deleteAllByUserId(targetUser.id)
    }

    @Transactional
    fun activateMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 활성화 처리 대상이 아닙니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)
        if (targetUser.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "소프트 삭제된 회원은 활성화할 수 없습니다.")
        }

        targetUser.status = UserStatus.ACTIVE
        userRepository.save(targetUser)
    }

    @Transactional
    fun softDeleteMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 삭제 처리할 수 없습니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)
        markUserSoftDeleted(targetUser)
        userRepository.save(targetUser)
        loginSessionStore.deleteAllByUserId(targetUser.id)
    }

    @Transactional
    fun restoreSoftDeletedMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 복구 대상이 아닙니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)
        if (targetUser.status != UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "소프트 삭제된 회원만 복구할 수 있습니다.")
        }

        val originalEmail = targetUser.deletedOriginalEmail
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "복구에 필요한 원본 이메일 정보가 없습니다.")
        val originalName = targetUser.deletedOriginalName
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "복구에 필요한 원본 이름 정보가 없습니다.")

        if (userRepository.existsByEmailAndIdNot(originalEmail, targetUser.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "원래 이메일을 현재 다른 계정이 사용 중이라 복구할 수 없습니다.")
        }

        targetUser.email = originalEmail
        targetUser.name = originalName
        targetUser.status = UserStatus.ACTIVE
        targetUser.deletedOriginalEmail = null
        targetUser.deletedOriginalName = null
        targetUser.deletedAt = null
        userRepository.save(targetUser)
    }

    @Transactional
    fun hardDeleteMemberByAdmin(adminPrincipal: AuthPrincipal, targetUserId: Long) {
        val adminUser = authorizeAdmin(adminPrincipal)
        if (adminUser.id == targetUserId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정은 삭제 처리할 수 없습니다.")
        }

        val targetUser = findUserOrNotFound(targetUserId)
        userFileRepository.deleteAllByUser_Id(targetUser.id)
        userRepository.delete(targetUser)
        loginSessionStore.deleteAllByUserId(targetUser.id)
    }

    @Transactional(readOnly = true)
    fun getMyProfile(principal: AuthPrincipal): UserProfileResponse {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { unauthorizedException() }
        return toProfileResponse(user)
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

    private fun findUserOrNotFound(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.") }
    }

    private fun markUserSoftDeleted(user: User) {
        if (user.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 삭제 처리된 회원입니다.")
        }
        if (user.deletedOriginalEmail.isNullOrBlank()) {
            user.deletedOriginalEmail = user.email
        }
        if (user.deletedOriginalName.isNullOrBlank()) {
            user.deletedOriginalName = user.name
        }
        user.email = buildDeletedEmailAlias(user.id)
        user.name = buildDeletedNameAlias(user.id)
        user.status = UserStatus.DELETED
        user.deletedAt = java.time.OffsetDateTime.now()
    }

    private fun buildDeletedEmailAlias(userId: Long): String = "deletedUser$userId@vlainter.online"

    private fun buildDeletedNameAlias(userId: Long): String = "Deleted User $userId"

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

    private fun toProfileResponse(user: User): UserProfileResponse {
        val normalizedUniversity = user.universityName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedDepartment = user.departmentName?.trim()?.takeIf { it.isNotBlank() }
        val university = normalizedUniversity?.let { academicSearchService.findUniversityByName(it) }
        val department = if (university != null && normalizedDepartment != null) {
            university.universityId?.let { academicSearchService.findDepartmentByName(it, normalizedDepartment) }
        } else {
            null
        }
        return UserProfileResponse(
            email = user.email,
            name = user.name,
            role = user.role,
            status = user.status,
            point = user.point,
            serviceMode = user.serviceMode,
            universityId = university?.universityId,
            universityName = normalizedUniversity,
            departmentId = department?.departmentId,
            departmentName = normalizedDepartment,
            hasAcademicProfile = normalizedUniversity != null && normalizedDepartment != null,
            hasGeminiApiKey = userGeminiApiKeyService.hasGeminiApiKey(user),
            hasProfileImage = userFileRepository.findTopByUser_IdAndFileTypeAndIsActiveTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
                user.id,
                FileType.PROFILE_IMAGE
            ) != null
        )
    }

    private fun String?.normalizeAcademicText(fieldName: String): String? {
        val normalized = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.length > 120) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${fieldName}은 120자를 초과할 수 없습니다.")
        }
        return normalized
    }

    private inline fun <T> resolveAcademicInputOrThrow(action: () -> T): T {
        return try {
            action()
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "학적 정보를 저장할 수 없습니다.", ex)
        } catch (ex: ResponseStatusException) {
            if (ex.statusCode == HttpStatus.BAD_REQUEST) {
                throw ex
            }
            throw ex
        }
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

    private fun toAdminMemberDetailResponse(user: User, refreshAccess: Boolean = false): AdminMemberDetailResponse {
        val accessSummary = authAccessAuditService.getSummaryForUser(user.id, 7, refreshAccess)
        val lastLogin = authAccessAuditService.getLastLoginForUser(user.id)
        val recentAccessLogs = authAccessAuditService.getRecentEntriesForUser(user.id, 8).map { entry ->
            AdminMemberAccessLogResponse(
                sessionIdPrefix = entry.sessionId.take(8),
                authProvider = entry.authProvider.name,
                loginAt = entry.loginAt,
                lastActivityAt = entry.lastActivityAt,
                logoutAt = entry.logoutAt,
                actionCount = entry.actionCount,
                ipAddress = entry.ipAddress,
                browser = toBrowserLabel(entry.userAgent),
                deviceType = toDeviceLabel(entry.userAgent),
                active = entry.active
            )
        }
        return AdminMemberDetailResponse(
            memberId = user.id,
            email = user.email,
            name = user.name,
            status = user.status,
            role = user.role,
            point = user.point,
            free = user.free,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            accessSummary = AdminMemberAccessSummaryResponse(
                recentLoginCount = accessSummary.recentLoginCount,
                activeSessionCount = accessSummary.activeSessionCount,
                totalActionCount = accessSummary.totalActionCount,
                averageActionCount = accessSummary.averageActionCount,
                averageSessionMinutes = accessSummary.averageSessionMinutes,
                lastLoginAt = lastLogin?.loginAt ?: accessSummary.lastLoginAt,
                lastLoginIpAddress = lastLogin?.ipAddress,
                completedInterviewCount = accessSummary.completedInterviewCount,
                totalInterviewCount = accessSummary.totalInterviewCount,
                interviewCompletionRate = accessSummary.interviewCompletionRate,
                dailyLoginCounts = accessSummary.dailyLoginCounts.map {
                    AdminMemberAccessDailyCountResponse(
                        date = it.date,
                        loginCount = it.loginCount
                    )
                },
                calculatedAt = accessSummary.calculatedAt
            ),
            recentAccessLogs = recentAccessLogs
        )
    }

    private fun toBrowserLabel(userAgent: String?): String {
        val ua = userAgent.orEmpty().lowercase()
        return when {
            "edg/" in ua -> "Edge"
            "whale/" in ua -> "Whale"
            "kakaotalk" in ua -> "KakaoTalk"
            "chrome/" in ua && "safari/" in ua -> "Chrome"
            "safari/" in ua && "chrome/" !in ua -> "Safari"
            "firefox/" in ua -> "Firefox"
            "android" in ua -> "Android WebView"
            else -> "기타"
        }
    }

    private fun toDeviceLabel(userAgent: String?): String {
        val ua = userAgent.orEmpty().lowercase()
        return when {
            "ipad" in ua || "tablet" in ua -> "태블릿"
            "iphone" in ua || ("android" in ua && "mobile" in ua) -> "모바일"
            "macintosh" in ua || "windows" in ua || "linux" in ua -> "데스크톱"
            else -> "기타"
        }
    }
}
