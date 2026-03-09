package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.QuestionSummaryResponse
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
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
        val normalizedJobName = request.jobName.trim()
        val normalizedSkillName = request.skillName.trim()
        jobSkillCatalogService.ensureCatalog(normalizedJobName, normalizedSkillName)
        val saved = questionSetRepository.save(
            QaQuestionSet(
                ownerUser = actor,
                ownerType = QuestionSetOwnerType.USER,
                title = request.title.trim(),
                jobName = normalizedJobName,
                skillName = normalizedSkillName,
                description = request.description?.trim(),
                visibility = request.visibility
            )
        )
        return toSummary(saved, 0)
    }

    @Transactional(readOnly = true)
    fun getMyAndGlobalSets(principal: AuthPrincipal): List<QuestionSetSummaryResponse> {
        val mySets = questionSetRepository.findVisibleUserSets(principal.userId)
        val globalSets = questionSetRepository.findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            visibility = QuestionSetVisibility.GLOBAL,
            status = QuestionSetStatus.ACTIVE
        )

        val merged = (mySets + globalSets)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }

        return merged.map { set ->
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
        val actor = loadUser(principal.userId)
        val set = getAccessibleSet(principal, setId)
        if (set.status != QuestionSetStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "ARCHIVED 상태의 세트에는 질문을 추가할 수 없습니다.")
        }

        val context = categoryContextResolver.resolve(
            actor = actor,
            categoryId = request.categoryId,
            jobName = request.jobName,
            skillName = request.skillName,
            createIfMissing = true
        ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문 세트에는 직무와 기술 입력이 필요합니다.")

        val existingJob = set.jobName?.trim().orEmpty()
        if (existingJob.isNotBlank() && !existingJob.equals(context.jobName, ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "질문 세트 직무(${existingJob})와 추가 질문 직무(${context.jobName})가 일치하지 않습니다."
            )
        }
        val existingSkill = set.skillName?.trim().orEmpty()
        if (existingSkill.isNotBlank() && !existingSkill.equals(context.skillName, ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "질문 세트 기술(${existingSkill})과 추가 질문 기술(${context.skillName})이 일치하지 않습니다."
            )
        }

        jobSkillCatalogService.ensureCatalog(context.jobName, context.skillName)
        if (set.jobName.isNullOrBlank()) set.jobName = context.jobName
        if (set.skillName.isNullOrBlank()) set.skillName = context.skillName

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

    @Transactional(readOnly = true)
    fun getSetQuestions(principal: AuthPrincipal, setId: Long): List<QuestionSummaryResponse> {
        val set = getAccessibleSet(principal, setId)
        return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id)
            .map { toQuestionSummary(it.question) }
    }

    @Transactional(readOnly = true)
    fun getAllSetsForAdmin(principal: AuthPrincipal): List<QuestionSetSummaryResponse> {
        ensureAdmin(principal)
        return questionSetRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .map { set ->
                val count = questionSetItemRepository.countBySet_IdAndIsActiveTrue(set.id).toInt()
                toSummary(set, count)
            }
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
                title = "${sourceSet.title} (공용)",
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

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근 가능합니다.")
        }
    }

    private fun loadUser(userId: Long) = userRepository.findById(userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자 정보를 찾을 수 없습니다.") }

    private fun toSummary(set: QaQuestionSet, questionCount: Int): QuestionSetSummaryResponse {
        return QuestionSetSummaryResponse(
            setId = set.id,
            title = set.title,
            description = set.description,
            jobName = set.jobName,
            skillName = set.skillName,
            ownerType = set.ownerType,
            visibility = set.visibility,
            questionCount = questionCount,
            isPromoted = set.isPromoted,
            createdAt = set.createdAt
        )
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
