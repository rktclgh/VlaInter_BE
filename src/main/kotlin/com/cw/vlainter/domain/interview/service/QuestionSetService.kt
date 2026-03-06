package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.EmbeddingRequestResponse
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.QuestionSummaryResponse
import com.cw.vlainter.domain.interview.entity.EmbeddingStatus
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
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
    private val categoryRepository: QaCategoryRepository,
    private val questionSetRepository: QaQuestionSetRepository,
    private val questionRepository: QaQuestionRepository,
    private val questionSetItemRepository: QaQuestionSetItemRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun createMySet(principal: AuthPrincipal, request: CreateQuestionSetRequest): QuestionSetSummaryResponse {
        val actor = loadUser(principal.userId)
        val saved = questionSetRepository.save(
            QaQuestionSet(
                ownerUser = actor,
                ownerType = QuestionSetOwnerType.USER,
                title = request.title.trim(),
                description = request.description?.trim(),
                visibility = request.visibility
            )
        )
        return toSummary(saved, 0)
    }

    @Transactional(readOnly = true)
    fun getMyAndGlobalSets(principal: AuthPrincipal): List<QuestionSetSummaryResponse> {
        val mySets = questionSetRepository.findAllByOwnerUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(principal.userId)
        val globalSets = questionSetRepository.findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            visibility = QuestionSetVisibility.GLOBAL,
            status = QuestionSetStatus.ACTIVE
        )

        val merged = (mySets + globalSets)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }

        return merged.map { set ->
            val count = questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id).size
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

        val category = categoryRepository.findByIdAndDeletedAtIsNull(request.categoryId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 categoryId 입니다: ${request.categoryId}")
        if (!category.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리에는 질문을 추가할 수 없습니다.")
        }

        val sourceTag = when (set.ownerType) {
            QuestionSetOwnerType.ADMIN -> QuestionSourceTag.SYSTEM
            QuestionSetOwnerType.USER -> QuestionSourceTag.USER
        }
        val fingerprint = fingerprintFor(request.questionText, category.path, request.difficulty.name)

        val question = questionRepository.findByFingerprintAndDeletedAtIsNull(fingerprint)
            ?: questionRepository.save(
                QaQuestion(
                    fingerprint = fingerprint,
                    questionText = request.questionText.trim(),
                    canonicalAnswer = request.canonicalAnswer?.trim(),
                    category = category,
                    difficulty = request.difficulty,
                    sourceTag = sourceTag,
                    tagsJson = toJsonArray(request.tags)
                )
            )

        if (!questionSetItemRepository.existsBySet_IdAndQuestion_Id(set.id, question.id)) {
            val nextOrder = questionSetItemRepository.findMaxOrderNo(set.id) + 1
            questionSetItemRepository.save(
                QaQuestionSetItem(
                    set = set,
                    question = question,
                    orderNo = nextOrder
                )
            )
            if (set.embeddingStatus == EmbeddingStatus.READY) {
                set.embeddingStatus = EmbeddingStatus.NOT_EMBEDDED
                set.embeddedAt = null
                set.embeddingVersion = null
            }
        }

        return toQuestionSummary(question)
    }

    @Transactional
    fun requestEmbedding(principal: AuthPrincipal, setId: Long): EmbeddingRequestResponse {
        val set = getAccessibleSet(principal, setId)
        return when (set.embeddingStatus) {
            EmbeddingStatus.READY -> EmbeddingRequestResponse(
                setId = set.id,
                embeddingStatus = set.embeddingStatus,
                message = "이미 임베딩이 완료된 세트입니다."
            )

            EmbeddingStatus.QUEUED, EmbeddingStatus.PROCESSING -> EmbeddingRequestResponse(
                setId = set.id,
                embeddingStatus = set.embeddingStatus,
                message = "이미 임베딩 작업이 진행 중입니다."
            )

            else -> {
                set.embeddingStatus = EmbeddingStatus.QUEUED
                set.embeddingRequestedAt = OffsetDateTime.now()
                set.embeddingError = null
                EmbeddingRequestResponse(
                    setId = set.id,
                    embeddingStatus = set.embeddingStatus,
                    message = "임베딩 작업이 요청되었습니다."
                )
            }
        }
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
                val count = questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id).size
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
                description = sourceSet.description,
                visibility = QuestionSetVisibility.GLOBAL,
                status = QuestionSetStatus.ACTIVE,
                embeddingStatus = sourceSet.embeddingStatus,
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
            ownerType = set.ownerType,
            visibility = set.visibility,
            embeddingStatus = set.embeddingStatus,
            questionCount = questionCount,
            isPromoted = set.isPromoted,
            createdAt = set.createdAt
        )
    }

    private fun toQuestionSummary(question: QaQuestion): QuestionSummaryResponse {
        val resolved = resolveAnswerContent(
            questionText = question.questionText,
            rawModelAnswer = question.canonicalAnswer,
            rawGuideText = null,
            difficulty = question.difficulty.name,
            categoryLabel = question.category.name
        )
        return QuestionSummaryResponse(
            questionId = question.id,
            questionText = question.questionText,
            canonicalAnswer = resolved.modelAnswer,
            modelAnswer = resolved.modelAnswer,
            bestPractice = resolved.guideText,
            categoryId = question.category.id,
            categoryName = question.category.name,
            categoryPath = question.category.path,
            difficulty = question.difficulty,
            sourceTag = question.sourceTag,
            tags = parseJsonArray(question.tagsJson)
        )
    }

    private fun fingerprintFor(questionText: String, category: String, difficulty: String): String {
        val normalized = "${questionText.trim().lowercase()}|${category.trim().lowercase()}|${difficulty.trim().lowercase()}"
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
