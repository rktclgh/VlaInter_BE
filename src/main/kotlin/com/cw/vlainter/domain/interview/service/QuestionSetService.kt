package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.AdminQuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.QuestionSummaryResponse
import com.cw.vlainter.domain.interview.dto.UpdateQuestionInSetRequest
import com.cw.vlainter.domain.interview.dto.UpdateQuestionSetRequest
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.OffsetDateTime

@Service
class QuestionSetService(
    private val categoryContextResolver: InterviewCategoryContextResolver,
    private val jobSkillCatalogService: JobSkillCatalogService,
    private val questionSetRepository: QaQuestionSetRepository,
    private val questionRepository: QaQuestionRepository,
    private val questionSetItemRepository: QaQuestionSetItemRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun createMySet(principal: AuthPrincipal, request: CreateQuestionSetRequest): QuestionSetSummaryResponse {
        val actor = loadUser(principal.userId)
        val normalizedBranchName = (request.branchName ?: request.jobName).orEmpty().trim()
        if (normalizedBranchName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 세트에는 계열명이 필요합니다.")
        }
        request.skillName?.trim()?.takeIf { it.isNotBlank() }?.let { normalizedSkillName ->
            jobSkillCatalogService.ensureCatalog(normalizedBranchName, normalizedSkillName)
        }
        val saved = questionSetRepository.save(
            QaQuestionSet(
                ownerUser = actor,
                ownerType = QuestionSetOwnerType.USER,
                title = request.title.trim(),
                jobName = normalizedBranchName,
                skillName = null,
                description = request.description?.trim(),
                visibility = request.visibility
            )
        )
        return toSummary(saved, 0)
    }

    @Transactional
    fun getMyAndGlobalSets(principal: AuthPrincipal): List<QuestionSetSummaryResponse> {
        val mySets = getMySets(principal)
        val mySetIds = mySets.mapTo(linkedSetOf()) { it.setId }
        return mySets + getGlobalSets().filterNot { it.setId in mySetIds }
    }

    @Transactional
    fun getMySets(principal: AuthPrincipal): List<QuestionSetSummaryResponse> {
        return questionSetRepository.findVisibleUserSets(principal.userId).mapNotNull { set ->
            val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(set.id).toInt()
            if (isAiGeneratedSet(set, count)) {
                null
            } else {
                toSummary(set, count)
            }
        }
    }

    @Transactional
    fun getGlobalSets(): List<QuestionSetSummaryResponse> {
        return questionSetRepository.findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            visibility = QuestionSetVisibility.GLOBAL,
            status = QuestionSetStatus.ACTIVE
        )
            .map { set ->
                val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(set.id).toInt()
                toSummary(set, count)
            }
    }

    @Transactional
    fun addQuestionToSet(
        principal: AuthPrincipal,
        setId: Long,
        request: AddQuestionToSetRequest
    ): QuestionSummaryResponse {
        val set = getAccessibleSet(principal, setId)
        if (set.status != QuestionSetStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "ARCHIVED 상태의 세트에는 질문을 추가할 수 없습니다.")
        }

        val context = categoryContextResolver.resolve(
            categoryId = request.categoryId,
            jobName = request.jobName,
            skillName = request.skillName,
            requireIfMissing = true
        ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 세트에는 직무와 기술 입력이 필요합니다.")

        val existingBranch = set.jobName?.trim().orEmpty()
        if (existingBranch.isNotBlank() && !existingBranch.equals(context.branchName, ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "질문 세트 계열(${existingBranch})과 추가 질문 계열(${context.branchName})이 일치하지 않습니다."
            )
        }

        jobSkillCatalogService.ensureCatalog(context.jobName, context.skillName)
        if (set.jobName.isNullOrBlank()) set.jobName = context.branchName

        val sourceTag = when (set.ownerType) {
            QuestionSetOwnerType.ADMIN -> QuestionSourceTag.SYSTEM
            QuestionSetOwnerType.USER -> QuestionSourceTag.USER
        }
        val fingerprint = fingerprintFor(
            questionText = request.questionText,
            jobName = context.jobName,
            skillName = context.skillName,
            difficulty = request.difficulty.name
        )

        val question = questionRepository.findByFingerprintAndDeletedAtIsNull(fingerprint)
            ?: questionRepository.save(
                QaQuestion(
                    fingerprint = fingerprint,
                    questionText = request.questionText.trim(),
                    canonicalAnswer = request.canonicalAnswer?.trim(),
                    category = context.category,
                    jobName = context.jobName,
                    skillName = context.skillName,
                    difficulty = request.difficulty,
                    sourceTag = sourceTag,
                    tagsJson = toJsonArray(request.tags)
                )
            )

        if (questionSetItemRepository.existsBySet_IdAndQuestion_Id(set.id, question.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 질문 세트에 포함된 질문입니다.")
        }
        val nextOrder = questionSetItemRepository.findMaxOrderNo(set.id) + 1
        questionSetItemRepository.save(
            QaQuestionSetItem(
                set = set,
                question = question,
                orderNo = nextOrder
            )
        )

        return toQuestionSummary(question)
    }

    @Transactional
    fun updateQuestionInSet(
        principal: AuthPrincipal,
        setId: Long,
        questionId: Long,
        request: UpdateQuestionInSetRequest
    ): QuestionSummaryResponse {
        val set = getOwnedUserSet(principal, setId)
        if (set.status != QuestionSetStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "ARCHIVED 상태의 세트 문답은 수정할 수 없습니다.")
        }
        val setItem = questionSetItemRepository.findBySet_IdAndQuestion_IdAndIsActiveTrue(set.id, questionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "세트에 포함된 질문을 찾을 수 없습니다.")

        val context = categoryContextResolver.resolve(
            categoryId = request.categoryId,
            jobName = request.jobName,
            skillName = request.skillName,
            requireIfMissing = true
        ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 수정에는 직무와 기술 입력이 필요합니다.")

        val existingBranch = set.jobName?.trim().orEmpty()
        if (existingBranch.isNotBlank() && !existingBranch.equals(context.branchName, ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "질문 세트 계열(${existingBranch})과 수정 질문 계열(${context.branchName})이 일치하지 않습니다."
            )
        }

        jobSkillCatalogService.ensureCatalog(context.jobName, context.skillName)
        if (set.jobName.isNullOrBlank()) set.jobName = context.branchName

        val sourceTag = when (set.ownerType) {
            QuestionSetOwnerType.ADMIN -> QuestionSourceTag.SYSTEM
            QuestionSetOwnerType.USER -> QuestionSourceTag.USER
        }
        val fingerprint = fingerprintFor(
            questionText = request.questionText,
            jobName = context.jobName,
            skillName = context.skillName,
            difficulty = request.difficulty.name
        )

        val updatedQuestion = questionRepository.findByFingerprintAndDeletedAtIsNull(fingerprint)
            ?: questionRepository.save(
                QaQuestion(
                    fingerprint = fingerprint,
                    questionText = request.questionText.trim(),
                    canonicalAnswer = request.canonicalAnswer?.trim(),
                    category = context.category,
                    jobName = context.jobName,
                    skillName = context.skillName,
                    difficulty = request.difficulty,
                    sourceTag = sourceTag,
                    tagsJson = toJsonArray(request.tags)
                )
            )

        if (updatedQuestion.id != questionId && questionSetItemRepository.existsBySet_IdAndQuestion_Id(set.id, updatedQuestion.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 질문 세트에 포함된 질문입니다.")
        }

        setItem.question = updatedQuestion
        questionSetItemRepository.save(setItem)
        return toQuestionSummary(updatedQuestion)
    }

    @Transactional
    fun deleteQuestionFromSet(principal: AuthPrincipal, setId: Long, questionId: Long) {
        val set = getOwnedUserSet(principal, setId)
        val setItem = questionSetItemRepository.findBySet_IdAndQuestion_IdAndIsActiveTrue(set.id, questionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "세트에 포함된 질문을 찾을 수 없습니다.")
        setItem.isActive = false
        questionSetItemRepository.save(setItem)
    }

    @Transactional(readOnly = true)
    fun getSetQuestions(principal: AuthPrincipal, setId: Long): List<QuestionSummaryResponse> {
        val set = getAccessibleSet(principal, setId)
        return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id)
            .map { toQuestionSummary(it.question) }
    }

    @Transactional
    fun getAllSetsForAdmin(principal: AuthPrincipal, keyword: String? = null): List<AdminQuestionSetSummaryResponse> {
        ensureAdmin(principal)
        val normalizedKeyword = keyword?.trim().orEmpty().lowercase()
        return questionSetRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .filter { set ->
                if (normalizedKeyword.isBlank()) return@filter true
                listOf(
                    set.title,
                    set.description,
                    set.jobName,
                    set.skillName,
                    set.ownerUser?.name,
                    set.ownerUser?.id?.toString()
                )
                    .filterNotNull()
                    .joinToString(" ")
                    .lowercase()
                    .contains(normalizedKeyword)
            }
            .map { set ->
                val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(set.id).toInt()
                toAdminSummary(set, count)
            }
    }

    @Transactional
    fun updateSetByAdmin(
        principal: AuthPrincipal,
        setId: Long,
        request: UpdateQuestionSetRequest
    ): QuestionSetSummaryResponse {
        ensureAdmin(principal)
        val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")

        request.title?.let { title ->
            val normalized = title.trim()
            if (normalized.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 세트 제목은 비어 있을 수 없습니다.")
            }
            set.title = normalized
        }
        request.description?.let { set.description = it.trim().ifBlank { null } }
        request.visibility?.let { set.visibility = it }
        request.status?.let { set.status = it }

        val saved = questionSetRepository.save(set)
        val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(saved.id).toInt()
        return toSummary(saved, count)
    }

    @Transactional
    fun deleteSetByAdmin(principal: AuthPrincipal, setId: Long) {
        ensureAdmin(principal)
        val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")
        softDeleteSet(set)
    }

    @Transactional
    fun updateMySet(
        principal: AuthPrincipal,
        setId: Long,
        request: UpdateQuestionSetRequest
    ): QuestionSetSummaryResponse {
        val set = getOwnedUserSet(principal, setId)
        request.title?.let { title ->
            val normalized = title.trim()
            if (normalized.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 세트 제목은 비어 있을 수 없습니다.")
            }
            set.title = normalized
        }
        request.description?.let { set.description = it.trim().ifBlank { null } }
        request.visibility?.let { set.visibility = it }
        request.status?.let { set.status = it }
        val saved = questionSetRepository.save(set)
        val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(saved.id).toInt()
        return toSummary(saved, count)
    }

    @Transactional
    fun deleteMySet(principal: AuthPrincipal, setId: Long) {
        val set = getOwnedUserSet(principal, setId)
        softDeleteSet(set)
    }

    @Transactional
    fun promoteSet(principal: AuthPrincipal, setId: Long): QuestionSetSummaryResponse {
        ensureAdmin(principal)
        val sourceSet = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")

        val actor = loadUser(principal.userId)
        val promoted = questionSetRepository.save(
            QaQuestionSet(
                ownerUser = actor,
                ownerType = QuestionSetOwnerType.ADMIN,
                title = normalizeLegacyTitle(sourceSet.title),
                jobName = sourceSet.jobName,
                skillName = sourceSet.skillName,
                description = sourceSet.description,
                visibility = QuestionSetVisibility.GLOBAL,
                status = QuestionSetStatus.ACTIVE,
                sourceSet = sourceSet,
                promotedBy = actor,
                promotedAt = OffsetDateTime.now(),
                isPromoted = true
            )
        )

        val sourceItems = questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(sourceSet.id)
        sourceItems.forEach { item ->
            questionSetItemRepository.save(
                QaQuestionSetItem(
                    set = promoted,
                    question = item.question,
                    orderNo = item.orderNo,
                    isActive = true
                )
            )
        }

        return toSummary(promoted, sourceItems.size)
    }

    private fun getAccessibleSet(principal: AuthPrincipal, setId: Long): QaQuestionSet {
        val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")

        val ownSet = set.ownerUser?.id == principal.userId
        val globalSet = set.visibility == QuestionSetVisibility.GLOBAL
        val admin = principal.role == UserRole.ADMIN
        if (!ownSet && !globalSet && !admin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "이 질문 세트에 접근할 수 없습니다.")
        }
        return set
    }

    private fun getOwnedUserSet(principal: AuthPrincipal, setId: Long): QaQuestionSet {
        val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")
        val ownerId = set.ownerUser?.id
        if (ownerId != principal.userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "소유자만 질문 세트를 수정/삭제할 수 있습니다.")
        }
        if (set.ownerType != QuestionSetOwnerType.USER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "사용자 생성 질문 세트만 수정/삭제할 수 있습니다.")
        }
        return set
    }

    private fun softDeleteSet(set: QaQuestionSet) {
        val now = OffsetDateTime.now()
        set.deletedAt = now
        set.status = QuestionSetStatus.ARCHIVED
        val items = questionSetItemRepository.findAllBySet_IdOrderByOrderNoAsc(set.id).onEach { item ->
            item.isActive = false
        }
        questionSetItemRepository.saveAll(items)
        questionSetRepository.save(set)
    }

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근 가능합니다.")
        }
    }

    private fun loadUser(userId: Long) = userRepository.findById(userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자 정보를 찾을 수 없습니다.") }

    private fun toSummary(set: QaQuestionSet, questionCount: Int): QuestionSetSummaryResponse {
        val owner = set.ownerUser
        val certified = set.ownerType == QuestionSetOwnerType.ADMIN || set.isPromoted
        val aiGenerated = isAiGeneratedSet(set, questionCount)
        val branchName = set.jobName
        val jobNames = extractJobNames(set.id)
        val skillNames = extractSkillNames(set.id)
        return QuestionSetSummaryResponse(
            setId = set.id,
            title = normalizeLegacyTitle(set.title),
            description = set.description,
            branchName = branchName,
            jobName = branchName,
            jobNames = jobNames,
            skillName = skillNames.firstOrNull() ?: set.skillName,
            skillNames = skillNames,
            ownerName = owner?.name,
            ownerType = set.ownerType,
            visibility = set.visibility,
            status = set.status,
            questionCount = questionCount,
            certified = certified,
            aiGenerated = aiGenerated,
            isPromoted = set.isPromoted,
            createdAt = set.createdAt
        )
    }

    private fun toAdminSummary(set: QaQuestionSet, questionCount: Int): AdminQuestionSetSummaryResponse {
        val owner = set.ownerUser
        val certified = set.ownerType == QuestionSetOwnerType.ADMIN || set.isPromoted
        val aiGenerated = isAiGeneratedSet(set, questionCount)
        val branchName = set.jobName
        val jobNames = extractJobNames(set.id)
        val skillNames = extractSkillNames(set.id)
        return AdminQuestionSetSummaryResponse(
            setId = set.id,
            title = normalizeLegacyTitle(set.title),
            description = set.description,
            branchName = branchName,
            jobName = branchName,
            jobNames = jobNames,
            skillName = skillNames.firstOrNull() ?: set.skillName,
            skillNames = skillNames,
            ownerUserId = owner?.id,
            ownerName = owner?.name,
            ownerType = set.ownerType,
            visibility = set.visibility,
            status = set.status,
            questionCount = questionCount,
            certified = certified,
            aiGenerated = aiGenerated,
            isPromoted = set.isPromoted,
            createdAt = set.createdAt
        )
    }

    private fun isAiGeneratedSet(set: QaQuestionSet, questionCount: Int): Boolean {
        if (set.ownerType != QuestionSetOwnerType.USER || questionCount <= 0) {
            return false
        }
        val systemQuestionCount = questionSetItemRepository
            .countBySet_IdAndIsActiveTrueAndQuestion_SourceTag(
                set.id,
                QuestionSourceTag.SYSTEM
            )
            .toInt()
        return systemQuestionCount == questionCount
    }

    private fun normalizeLegacyTitle(raw: String): String {
        var normalized = raw.trim()
        if (normalized.startsWith("AUTO:", ignoreCase = true)) {
            normalized = normalized.substringAfter(":").trim()
        }
        if (normalized.endsWith("(공용)")) {
            normalized = normalized.removeSuffix("(공용)").trim()
        }
        if (normalized.isBlank()) {
            normalized = "AI 질문 세트"
        }
        return normalized
    }

    private fun toQuestionSummary(question: QaQuestion): QuestionSummaryResponse {
        val normalizedAnswer = question.canonicalAnswer
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isGuideLikeText(it) }
        return QuestionSummaryResponse(
            questionId = question.id,
            questionText = question.questionText,
            canonicalAnswer = normalizedAnswer,
            modelAnswer = normalizedAnswer,
            bestPractice = null,
            categoryId = question.category.id,
            categoryName = question.skillName ?: question.category.name,
            jobName = question.jobName,
            skillName = question.skillName ?: question.category.name,
            difficulty = question.difficulty,
            sourceTag = question.sourceTag,
            tags = parseJsonArray(question.tagsJson)
        )
    }

    private fun extractSkillNames(setId: Long): List<String> {
        return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(setId)
            .mapNotNull { item ->
                item.question.skillName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: item.question.category.name.trim().takeIf { it.isNotBlank() }
            }
            .distinctBy { it.lowercase() }
    }

    private fun extractJobNames(setId: Long): List<String> {
        return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(setId)
            .mapNotNull { item ->
                item.question.jobName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinctBy { it.lowercase() }
    }

    private fun fingerprintFor(questionText: String, jobName: String, skillName: String, difficulty: String): String {
        val normalized = "${questionText.trim().lowercase()}|${jobName.trim().lowercase()}|${skillName.trim().lowercase()}|${difficulty.trim().lowercase()}"
            .replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun toJsonArray(values: List<String>): String {
        return objectMapper.writeValueAsString(values.map { it.trim() }.filter { it.isNotBlank() }.distinct())
    }

    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(raw, object : TypeReference<List<String>>() {})
        }.getOrDefault(emptyList())
    }
}
