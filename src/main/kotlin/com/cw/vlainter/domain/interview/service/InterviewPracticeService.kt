package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.BookmarkTurnRequest
import com.cw.vlainter.domain.interview.dto.InterviewQuestionResponse
import com.cw.vlainter.domain.interview.dto.QuestionAttemptResponse
import com.cw.vlainter.domain.interview.dto.SavedQuestionResponse
import com.cw.vlainter.domain.interview.dto.StartTechInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerRequest
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerResponse
import com.cw.vlainter.domain.interview.dto.TurnEvaluationResponse
import com.cw.vlainter.domain.interview.entity.DocumentQuestion
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.cw.vlainter.domain.interview.entity.InterviewMode
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.RevealPolicy
import com.cw.vlainter.domain.interview.entity.SavedQuestion
import com.cw.vlainter.domain.interview.entity.TurnEvaluationStatus
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import com.cw.vlainter.domain.interview.entity.UserQuestionAttempt
import com.cw.vlainter.domain.interview.repository.InterviewSessionRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnEvaluationRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.DocumentQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.interview.repository.SavedQuestionRepository
import com.cw.vlainter.domain.interview.repository.UserQuestionAttemptRepository
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.math.min

@Service
class InterviewPracticeService(
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val categoryRepository: QaCategoryRepository,
    private val questionRepository: QaQuestionRepository,
    private val questionSetRepository: QaQuestionSetRepository,
    private val questionSetItemRepository: QaQuestionSetItemRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    private val interviewTurnRepository: InterviewTurnRepository,
    private val interviewTurnEvaluationRepository: InterviewTurnEvaluationRepository,
    private val documentQuestionRepository: DocumentQuestionRepository,
    private val userQuestionAttemptRepository: UserQuestionAttemptRepository,
    private val savedQuestionRepository: SavedQuestionRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun startTechInterview(principal: AuthPrincipal, request: StartTechInterviewRequest): StartTechInterviewResponse {
        val actor = loadUser(principal.userId)
        val candidates = resolveCandidates(principal, request)
        if (candidates.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "조건에 맞는 질문이 없습니다.")
        }

        val questionCount = request.questionCount.coerceAtMost(candidates.size)
        val selected = candidates.shuffled().take(questionCount)
        val questionRefs = selected.map { QuestionRef(InterviewQuestionKind.TECH, it.id) }

        val session = interviewSessionRepository.save(
            InterviewSession(
                user = actor,
                mode = InterviewMode.TECH,
                status = InterviewStatus.IN_PROGRESS,
                questionSet = request.setId?.let { questionSetRepository.findByIdAndDeletedAtIsNull(it) },
                revealPolicy = RevealPolicy.PER_TURN,
                configJson = toSessionConfigJson(questionRefs = questionRefs, cursor = 1)
            )
        )

        val firstTurn = createTurnFromRef(session, 1, questionRefs.first())

        return StartTechInterviewResponse(
            sessionId = session.id,
            status = session.status.name,
            currentQuestion = toInterviewQuestionResponse(firstTurn),
            hasNext = questionCount > 1
        )
    }

    @Transactional
    fun submitAnswer(
        principal: AuthPrincipal,
        sessionId: Long,
        request: SubmitInterviewAnswerRequest
    ): SubmitInterviewAnswerResponse {
        val session = interviewSessionRepository.findByIdAndUser_Id(sessionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "면접 세션을 찾을 수 없습니다.")

        if (session.status != InterviewStatus.IN_PROGRESS) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "진행 중인 면접이 아닙니다.")
        }

        val turn = interviewTurnRepository.findFirstBySession_IdAndUserAnswerIsNullOrderByTurnNoAsc(session.id)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "답변할 질문이 없습니다.")

        turn.userAnswer = request.answer.trim()
        turn.answeredAt = OffsetDateTime.now()

        val evaluation = evaluate(turn, turn.userAnswer.orEmpty())
        turn.evaluationStatus = TurnEvaluationStatus.DONE
        interviewTurnEvaluationRepository.save(
            InterviewTurnEvaluation(
                turn = turn,
                totalScore = evaluation.score,
                feedback = evaluation.feedback,
                bestPractice = evaluation.bestPractice,
                rubricScoresJson = evaluation.rubricScoresJson,
                evidenceJson = evaluation.evidenceJson,
                model = evaluation.model,
                modelVersion = evaluation.modelVersion
            )
        )

        if (turn.question != null) {
            userQuestionAttemptRepository.save(
                UserQuestionAttempt(
                    user = session.user,
                    question = turn.question,
                    session = session,
                    turn = turn,
                    answerText = turn.userAnswer.orEmpty(),
                    totalScore = evaluation.score,
                    feedbackSummary = evaluation.feedback
                )
            )
        }

        val config = parseSessionConfig(session.configJson)
        val nextRef = if (config.cursor < config.queue.size) config.queue[config.cursor] else null

        val nextTurn = if (nextRef != null) {
            session.configJson = toSessionConfigJson(config.queue, config.cursor + 1)
            createTurnFromRef(session, turn.turnNo + 1, nextRef)
        } else {
            session.status = InterviewStatus.DONE
            session.finishedAt = OffsetDateTime.now()
            null
        }

        return SubmitInterviewAnswerResponse(
            sessionId = session.id,
            answeredTurnId = turn.id,
            evaluation = TurnEvaluationResponse(
                score = evaluation.score,
                feedback = evaluation.feedback,
                bestPractice = evaluation.bestPractice
            ),
            nextQuestion = nextTurn?.let { toInterviewQuestionResponse(it) },
            completed = nextTurn == null
        )
    }

    @Transactional
    fun bookmarkTurn(principal: AuthPrincipal, turnId: Long, request: BookmarkTurnRequest): SavedQuestionResponse {
        val turn = interviewTurnRepository.findByIdAndSession_User_Id(turnId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "북마크할 질문을 찾을 수 없습니다.")

        if (savedQuestionRepository.existsByUser_IdAndSourceTurn_Id(principal.userId, turn.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 저장된 질문입니다.")
        }

        turn.isBookmarked = true
        val saved = savedQuestionRepository.save(
            SavedQuestion(
                user = turn.session.user,
                question = turn.question,
                documentQuestion = turn.documentQuestion,
                sourceTurn = turn,
                questionTextSnapshot = turn.questionTextSnapshot,
                categorySnapshot = turn.categorySnapshot,
                category = turn.category,
                difficulty = turn.difficulty,
                sourceTag = turn.sourceTag.name,
                tagsJson = turn.tagsJson,
                note = request.note?.trim()
            )
        )
        return toSavedQuestionResponse(saved)
    }

    @Transactional(readOnly = true)
    fun getSavedQuestions(principal: AuthPrincipal): List<SavedQuestionResponse> {
        return savedQuestionRepository.findAllByUser_IdOrderByCreatedAtDesc(principal.userId)
            .map { toSavedQuestionResponse(it) }
    }

    @Transactional
    fun deleteSavedQuestion(principal: AuthPrincipal, savedQuestionId: Long) {
        val saved = savedQuestionRepository.findByIdAndUser_Id(savedQuestionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 질문을 찾을 수 없습니다.")
        savedQuestionRepository.delete(saved)
    }

    @Transactional(readOnly = true)
    fun getMyAttemptsByQuestion(principal: AuthPrincipal, questionId: Long): List<QuestionAttemptResponse> {
        return userQuestionAttemptRepository.findAllByUser_IdAndQuestion_IdOrderByCreatedAtDesc(principal.userId, questionId)
            .map {
                QuestionAttemptResponse(
                    attemptId = it.id,
                    sessionId = it.session?.id,
                    turnId = it.turn?.id,
                    answerText = it.answerText,
                    score = it.totalScore,
                    feedbackSummary = it.feedbackSummary,
                    createdAt = it.createdAt
                )
            }
    }

    private fun resolveCandidates(principal: AuthPrincipal, request: StartTechInterviewRequest): List<QaQuestion> {
        val filterCategoryIds = resolveCategoryIds(request.categoryId)
        if (request.setId != null) {
            val set = questionSetRepository.findByIdAndDeletedAtIsNull(request.setId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")
            val canAccess = set.ownerUser?.id == principal.userId || set.visibility == QuestionSetVisibility.GLOBAL
            if (!canAccess) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 질문 세트에 접근할 수 없습니다.")
            }
            return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id)
                .map { it.question }
                .filter { matchesFilter(it, request, filterCategoryIds) }
        }

        return questionRepository.findCandidatesForUser(
            userId = principal.userId,
            setStatus = QuestionSetStatus.ACTIVE,
            globalVisibility = QuestionSetVisibility.GLOBAL,
            difficulty = request.difficulty,
            sourceTag = request.sourceTag
        ).let { questions ->
            if (filterCategoryIds.isEmpty()) questions
            else questions.filter { it.category.id in filterCategoryIds }
        }
    }

    private fun matchesFilter(
        question: QaQuestion,
        request: StartTechInterviewRequest,
        categoryIds: Set<Long>
    ): Boolean {
        val categoryPass = categoryIds.isEmpty() || question.category.id in categoryIds
        val difficultyPass = request.difficulty == null || question.difficulty == request.difficulty
        val sourcePass = request.sourceTag == null || question.sourceTag == request.sourceTag
        return categoryPass && difficultyPass && sourcePass
    }

    private fun toTurnSource(question: QaQuestion): TurnSourceTag {
        return when (question.sourceTag) {
            com.cw.vlainter.domain.interview.entity.QuestionSourceTag.SYSTEM -> TurnSourceTag.SYSTEM
            com.cw.vlainter.domain.interview.entity.QuestionSourceTag.USER -> TurnSourceTag.USER
        }
    }

    private fun createTurnFromRef(session: InterviewSession, turnNo: Int, ref: QuestionRef): InterviewTurn {
        val turn = when (ref.kind) {
            InterviewQuestionKind.TECH -> {
                val question = questionRepository.findByIdAndDeletedAtIsNull(ref.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다: ${ref.id}")
                InterviewTurn(
                    session = session,
                    turnNo = turnNo,
                    sourceTag = toTurnSource(question),
                    question = question,
                    questionTextSnapshot = question.questionText,
                    categorySnapshot = question.category.name,
                    category = question.category,
                    difficulty = question.difficulty.name,
                    tagsJson = question.tagsJson
                )
            }

            InterviewQuestionKind.DOCUMENT -> {
                val question = documentQuestionRepository.findByIdAndUserId(ref.id, session.user.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문서 질문을 찾을 수 없습니다: ${ref.id}")
                InterviewTurn(
                    session = session,
                    turnNo = turnNo,
                    sourceTag = TurnSourceTag.DOC_RAG,
                    documentQuestion = question,
                    questionTextSnapshot = question.questionText,
                    categorySnapshot = question.questionType,
                    difficulty = question.difficulty,
                    tagsJson = """["${question.questionType}"]""",
                    ragContextJson = question.evidenceJson
                )
            }
        }

        return interviewTurnRepository.save(turn)
    }

    private fun toInterviewQuestionResponse(turn: InterviewTurn): InterviewQuestionResponse {
        return InterviewQuestionResponse(
            turnId = turn.id,
            turnNo = turn.turnNo,
            questionId = turn.question?.id,
            documentQuestionId = turn.documentQuestion?.id,
            questionKind = if (turn.question != null) InterviewQuestionKind.TECH else InterviewQuestionKind.DOCUMENT,
            categoryId = turn.category?.id,
            questionText = turn.questionTextSnapshot,
            sourceTag = turn.sourceTag,
            category = turn.categorySnapshot,
            difficulty = turn.difficulty,
            tags = parseTags(turn.tagsJson)
        )
    }

    private fun toSavedQuestionResponse(saved: SavedQuestion): SavedQuestionResponse {
        return SavedQuestionResponse(
            savedQuestionId = saved.id,
            questionId = saved.question?.id,
            documentQuestionId = saved.documentQuestion?.id,
            questionKind = if (saved.question != null) InterviewQuestionKind.TECH else InterviewQuestionKind.DOCUMENT,
            categoryId = saved.category?.id,
            questionText = saved.questionTextSnapshot,
            category = saved.categorySnapshot,
            difficulty = saved.difficulty,
            sourceTag = saved.sourceTag,
            tags = parseTags(saved.tagsJson),
            note = saved.note,
            createdAt = saved.createdAt
        )
    }

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(raw, object : TypeReference<List<String>>() {})
        }.getOrDefault(emptyList())
    }

    private fun resolveCategoryIds(categoryId: Long?): Set<Long> {
        if (categoryId == null) return emptySet()
        val category = categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 categoryId 입니다: $categoryId")
        if (!category.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다.")
        }
        val descendants = categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc("${category.path}/")
        return (listOf(category.id) + descendants.map { it.id }).toSet()
    }

    private fun loadUser(userId: Long) = userRepository.findById(userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자 정보를 찾을 수 없습니다.") }

    private fun toSessionConfigJson(questionRefs: List<QuestionRef>, cursor: Int): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "queue" to questionRefs,
                "cursor" to cursor
            )
        )
    }

    private fun parseSessionConfig(raw: String): SessionConfig {
        val node = runCatching { objectMapper.readTree(raw) }.getOrNull() ?: return SessionConfig()
        val queue = node["queue"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val type = item["kind"]?.asText()?.trim()?.uppercase()
                    ?: item["type"]?.asText()?.trim()?.uppercase()
                    ?: return@mapNotNull null
                val id = item["id"]?.asLong() ?: return@mapNotNull null
                runCatching { QuestionRef(InterviewQuestionKind.valueOf(type), id) }.getOrNull()
            }
            ?: node["questionIds"]?.mapNotNull { idNode ->
                if (!idNode.canConvertToLong()) return@mapNotNull null
                QuestionRef(InterviewQuestionKind.TECH, idNode.asLong())
            }
            ?: emptyList()
        val cursor = node["cursor"]?.asInt() ?: 0
        return SessionConfig(queue = queue, cursor = cursor)
    }

    private fun evaluate(turn: InterviewTurn, answer: String): EvaluationResult {
        val trimmed = answer.trim()
        if (trimmed.isBlank()) {
            return EvaluationResult(
                score = BigDecimal.ZERO.setScale(2),
                feedback = "답변이 비어 있습니다. 핵심 내용을 포함해 다시 작성해 주세요.",
                bestPractice = "질문 의도 1문장, 근거 2~3문장, 결론 1문장 구조로 답변하세요.",
                rubricScoresJson = """{"coverage":0,"accuracy":0,"communication":0}""",
                evidenceJson = "[]",
                model = "heuristic",
                modelVersion = "v1"
            )
        }

        val question = turn.question
        val documentQuestion = turn.documentQuestion
        val aiEvaluation = if (question != null) {
            interviewAiOrchestrator.evaluateTechAnswer(question, trimmed)
        } else if (documentQuestion != null) {
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = documentQuestion.questionText,
                referenceAnswer = documentQuestion.referenceAnswer,
                evidence = parseTags(documentQuestion.evidenceJson),
                userAnswer = trimmed
            )
        } else {
            null
        }
        if (aiEvaluation != null) {
            return EvaluationResult(
                score = aiEvaluation.score,
                feedback = aiEvaluation.feedback,
                bestPractice = aiEvaluation.bestPractice,
                rubricScoresJson = aiEvaluation.rubricScoresJson,
                evidenceJson = aiEvaluation.evidenceJson,
                model = aiEvaluation.model,
                modelVersion = aiEvaluation.modelVersion
            )
        }

        val canonical = when {
            question != null -> question.canonicalAnswer?.trim().orEmpty()
            documentQuestion != null -> documentQuestion.referenceAnswer?.trim().orEmpty()
            else -> ""
        }
        val score = if (canonical.isBlank()) {
            val lenScore = min(100, max(20, trimmed.length / 3))
            BigDecimal(lenScore).setScale(2)
        } else {
            val answerTokens = tokenize(trimmed)
            val canonicalTokens = tokenize(canonical)
            val overlap = if (canonicalTokens.isEmpty()) 0.0 else answerTokens.intersect(canonicalTokens).size.toDouble() / canonicalTokens.size
            val numeric = (30 + overlap * 70).coerceIn(0.0, 100.0)
            BigDecimal(numeric).setScale(2, RoundingMode.HALF_UP)
        }

        val feedback = when {
            score >= BigDecimal("85.00") -> "핵심 개념을 잘 설명했습니다. 근거 사례를 한 줄 추가하면 더 좋습니다."
            score >= BigDecimal("70.00") -> "핵심은 맞지만 설명 구조가 다소 약합니다. 결론을 먼저 말하고 근거를 붙여 보세요."
            else -> "핵심 포인트 누락이 있습니다. 용어 정의와 문제 해결 흐름을 분리해 작성해 보세요."
        }

        val bestPractice = if (canonical.isBlank()) {
            "질문 의도를 재진술한 뒤, 실무 예시와 트레이드오프를 포함해 답변하세요."
        } else {
            "모범답안의 핵심 키워드를 기준으로 1) 정의 2) 적용 사례 3) 한계/개선 순서로 답변하세요."
        }

        return EvaluationResult(score = score, feedback = feedback, bestPractice = bestPractice)
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    data class QuestionRef(
        val kind: InterviewQuestionKind,
        val id: Long
    )

    private data class SessionConfig(
        val queue: List<QuestionRef> = emptyList(),
        val cursor: Int = 0
    )

    private data class EvaluationResult(
        val score: BigDecimal,
        val feedback: String,
        val bestPractice: String,
        val rubricScoresJson: String = """{"coverage":0,"accuracy":0,"communication":0}""",
        val evidenceJson: String = "[]",
        val model: String = "heuristic",
        val modelVersion: String? = "v1"
    )
}
