package com.cw.vlainter.domain.interview.service
import com.cw.vlainter.domain.interview.dto.BookmarkTurnRequest
import com.cw.vlainter.domain.interview.dto.InterviewQuestionResponse
import com.cw.vlainter.domain.interview.dto.InterviewHistoryDocumentResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionHistoryPageResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionHistoryResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionResultsResponse
import com.cw.vlainter.domain.interview.dto.InterviewTurnResultResponse
import com.cw.vlainter.domain.interview.dto.QuestionAttemptResponse
import com.cw.vlainter.domain.interview.dto.ResumeInterviewSessionResponse
import com.cw.vlainter.domain.interview.dto.SavedQuestionResponse
import com.cw.vlainter.domain.interview.dto.StartTechInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerRequest
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerResponse
import com.cw.vlainter.domain.interview.dto.TurnEvaluationResponse
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.InterviewMode
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.RevealPolicy
import com.cw.vlainter.domain.interview.entity.SavedQuestion
import com.cw.vlainter.domain.interview.entity.TechQuestionReusePolicy
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import com.cw.vlainter.domain.interview.ai.AiRoutingContextHolder
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.ai.GeminiTransientException
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
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.system.measureTimeMillis

@Service
class InterviewPracticeService(
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val aiRoutingContextHolder: AiRoutingContextHolder,
    private val interviewEvaluationService: InterviewEvaluationService,
    private val jobSkillCatalogService: JobSkillCatalogService,
    private val categoryRepository: QaCategoryRepository,
    private val categoryContextResolver: InterviewCategoryContextResolver,
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
    private val userGeminiApiKeyService: UserGeminiApiKeyService,
    private val adminInterviewSettingsService: AdminInterviewSettingsService,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager
) {
    companion object {
        private const val AI_GENERATED_SET_DESCRIPTION = "카테고리 기반 자동 생성 문답"
        private const val TECH_HISTORY_SCAN_BATCH_SIZE = 24
        private val ENGLISH_WORD_REGEX = Regex("""\b[A-Za-z](?:[A-Za-z']*[A-Za-z])?\b""")
        private val ENGLISH_LETTER_REGEX = Regex("""[A-Za-z]""")
        private val HANGUL_REGEX = Regex("""[가-힣]""")
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun startTechInterview(principal: AuthPrincipal, request: StartTechInterviewRequest): StartTechInterviewResponse {
        aiRoutingContextHolder.reset()
        try {
            val actor = loadUser(principal.userId)
            userGeminiApiKeyService.assertGeminiApiKeyConfigured(actor.id)
            lateinit var response: StartTechInterviewResponse
            val elapsedMs = measureTimeMillis {
                var categoryContext = if (request.setId == null) {
                    categoryContextResolver.resolve(
                        categoryId = request.categoryId,
                        jobName = request.jobName,
                        skillName = request.skillName,
                        requireIfMissing = false
                    )
                } else {
                    null
                }
                val techQuestionReusePolicy = if (request.setId == null) {
                    adminInterviewSettingsService.getTechQuestionReusePolicy()
                } else {
                    TechQuestionReusePolicy.REUSE_MATCHING
                }
                var candidates = if (shouldReuseMatchingQuestions(request, techQuestionReusePolicy)) {
                    resolveCandidates(principal, request, categoryContext?.category?.id)
                } else {
                    emptyList()
                }
                if (candidates.isEmpty() && request.setId == null) {
                    categoryContext = categoryContext
                        ?: categoryContextResolver.resolve(
                            categoryId = request.categoryId,
                            jobName = request.jobName,
                            skillName = request.skillName,
                            requireIfMissing = false
                        )
                        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술질문 연습에는 기술 선택이 필요합니다.")
                    candidates = userGeminiApiKeyService.withUserApiKey(actor.id) {
                        generateCategoryQuestions(actor, request, categoryContext)
                    }
                }
                if (candidates.isEmpty()) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "조건에 맞는 질문이 없습니다.")
                }

                val questionCount = request.questionCount.coerceAtMost(candidates.size)
                val selected = candidates.shuffled().take(questionCount)
                val questionRefs = selected.map { QuestionRef(InterviewQuestionKind.TECH, it.id) }
                val localizedQueue = buildLocalizedTechQueueEntries(actor.id, request.language, selected)
                val questionSet = request.setId?.let { questionSetRepository.findByIdAndDeletedAtIsNull(it) }
                val primaryCategory = categoryContext?.category
                    ?: request.categoryId?.let { categoryRepository.findByIdAndDeletedAtIsNull(it) }
                    ?: selected.firstOrNull()?.category
                val jobCategory = primaryCategory?.parent ?: primaryCategory
                val resolvedJobName = request.jobName?.trim()?.takeIf { it.isNotBlank() }
                    ?: questionSet?.jobName
                    ?: categoryContext?.jobName
                    ?: jobCategory?.name
                val resolvedSkillName = request.skillName?.trim()?.takeIf { it.isNotBlank() }
                    ?: questionSet?.skillName
                    ?: categoryContext?.skillName
                    ?: primaryCategory?.name
                if (!resolvedJobName.isNullOrBlank() && !resolvedSkillName.isNullOrBlank()) {
                    jobSkillCatalogService.ensureCatalog(resolvedJobName, resolvedSkillName)
                }
                val practiceMode = if (request.setId != null) InterviewMode.QUESTION_SET_PRACTICE else InterviewMode.TECH
                val saveHistory = if (practiceMode == InterviewMode.QUESTION_SET_PRACTICE) false else request.saveHistory

                val session = interviewSessionRepository.save(
                    InterviewSession(
                        user = actor,
                        mode = practiceMode,
                        status = InterviewStatus.IN_PROGRESS,
                        questionSet = questionSet,
                        revealPolicy = RevealPolicy.PER_TURN,
                        configJson = toSessionConfigJson(
                            questionRefs = questionRefs,
                            cursor = 1,
                            meta = mapOf(
                                "saveHistory" to saveHistory,
                                "questionCount" to questionCount,
                                "difficulty" to request.difficulty?.name,
                                "difficultyRating" to difficultyToRating(request.difficulty),
                                "language" to request.language.name,
                                "categoryId" to primaryCategory?.id,
                                "categoryName" to resolvedSkillName,
                                "jobName" to resolvedJobName,
                                "practiceMode" to practiceMode.name,
                                "techQuestionReusePolicy" to techQuestionReusePolicy.name,
                                "selectedDocuments" to emptyList<Map<String, Any?>>(),
                                "localizedQueue" to localizedQueue,
                                "providerUsed" to aiRoutingContextHolder.snapshot().providerUsed?.name,
                                "fallbackDepth" to aiRoutingContextHolder.snapshot().fallbackDepth
                            )
                        )
                    )
                )

                val firstTurn = createTurnFromRef(session, 1, questionRefs.first())
                val routingSnapshot = aiRoutingContextHolder.snapshot()

                response = StartTechInterviewResponse(
                    sessionId = session.id,
                    status = session.status.name,
                    currentQuestion = toInterviewQuestionResponse(firstTurn),
                    hasNext = questionCount > 1,
                    language = request.language.name,
                    providerUsed = routingSnapshot.providerUsed?.name,
                    fallbackDepth = routingSnapshot.fallbackDepth
                )
            }
            logger.info(
                "tech interview start timing userId={} setId={} categoryId={} requestedQuestions={} elapsedMs={}",
                actor.id,
                request.setId,
                request.categoryId,
                request.questionCount,
                elapsedMs
            )
            return response
        } finally {
            aiRoutingContextHolder.clear()
        }
    }

    @Transactional
    fun submitTechAnswer(
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

        val submittedAnswer = request.answer.trim()
        assertInterviewAnswerLanguage(session, submittedAnswer)
        turn.userAnswer = submittedAnswer
        turn.answeredAt = OffsetDateTime.now()
        interviewTurnRepository.save(turn)
        entityManager.flush()

        val config = parseSessionConfig(session.configJson)
        val nextRef = if (config.cursor < config.queue.size) config.queue[config.cursor] else null

        val nextTurn = if (nextRef != null) {
            session.configJson = toSessionConfigJson(config.queue, config.cursor + 1, config.meta)
            createTurnFromRef(session, turn.turnNo + 1, nextRef)
        } else {
            session.status = InterviewStatus.FINISHING
            entityManager.flush()
            interviewEvaluationService.evaluateOutstandingTurnsSync(session.id)
            session.status = InterviewStatus.DONE
            session.finishedAt = OffsetDateTime.now()
            null
        }

        return SubmitInterviewAnswerResponse(
            sessionId = session.id,
            answeredTurnId = turn.id,
            submittedAnswer = submittedAnswer,
            nextQuestion = nextTurn?.let { toInterviewQuestionResponse(it) },
            completed = nextTurn == null
        )
    }

    @Transactional
    fun submitMockAnswer(
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

        val submittedAnswer = request.answer.trim()
        assertInterviewAnswerLanguage(session, submittedAnswer)
        turn.userAnswer = submittedAnswer
        turn.answeredAt = OffsetDateTime.now()
        interviewTurnRepository.save(turn)

        val config = parseSessionConfig(session.configJson)
        val nextRef = if (config.cursor < config.queue.size) config.queue[config.cursor] else null

        if (nextRef != null) {
            session.configJson = toSessionConfigJson(config.queue, config.cursor + 1, config.meta)
            val nextTurn = createTurnFromRef(session, turn.turnNo + 1, nextRef)

            return SubmitInterviewAnswerResponse(
                sessionId = session.id,
                answeredTurnId = turn.id,
                submittedAnswer = submittedAnswer,
                nextQuestion = toInterviewQuestionResponse(nextTurn),
                completed = false
            )
        }

        session.status = InterviewStatus.FINISHING
        entityManager.flush()
        interviewEvaluationService.evaluateOutstandingTurnsSync(session.id)
        session.status = InterviewStatus.DONE
        session.finishedAt = OffsetDateTime.now()

        return SubmitInterviewAnswerResponse(
            sessionId = session.id,
            answeredTurnId = turn.id,
            submittedAnswer = submittedAnswer,
            nextQuestion = null,
            completed = true
        )
    }

    @Transactional
    fun bookmarkTurn(principal: AuthPrincipal, turnId: Long, request: BookmarkTurnRequest): SavedQuestionResponse {
        val turn = interviewTurnRepository.findByIdAndSession_User_Id(turnId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "북마크할 질문을 찾을 수 없습니다.")
        if (turn.question == null && turn.documentQuestion == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "저장할 수 없는 문항입니다.")
        }

        val existingByQuestion = resolveExistingSavedQuestion(principal.userId, turn.question?.id, turn.documentQuestion?.id)
        if (existingByQuestion != null) {
            val nextNote = request.note?.trim()
            if (!nextNote.isNullOrBlank()) {
                existingByQuestion.note = nextNote
            }
            turn.isBookmarked = true
            return toSavedQuestionResponse(existingByQuestion)
        }

        if (savedQuestionRepository.existsByUser_IdAndSourceTurn_Id(principal.userId, turn.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 저장된 질문입니다.")
        }

        turn.isBookmarked = true
        val normalizedSourceTag = normalizedTurnSourceTag(turn)
        val saved = savedQuestionRepository.save(
            SavedQuestion(
                user = turn.session.user,
                question = turn.question,
                documentQuestion = turn.documentQuestion,
                sourceTurn = turn,
                questionTextSnapshot = turn.questionTextSnapshot,
                categorySnapshot = turn.categorySnapshot,
                jobSnapshot = turn.jobSnapshot,
                skillSnapshot = turn.skillSnapshot,
                category = turn.category,
                difficulty = turn.difficulty,
                sourceTag = normalizedSourceTag.name,
                tagsJson = turn.tagsJson,
                note = request.note?.trim()
            )
        )
        return toSavedQuestionResponse(saved)
    }

    @Transactional
    fun saveQuestion(principal: AuthPrincipal, questionId: Long, request: BookmarkTurnRequest): SavedQuestionResponse {
        val actor = loadUserForUpdate(principal.userId)
        val question = questionRepository.findByIdAndDeletedAtIsNull(questionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "저장할 질문을 찾을 수 없습니다.")
        val accessible = questionSetItemRepository.existsAccessibleByQuestionIdAndUserId(question.id, principal.userId)
        if (!accessible) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 질문에 접근할 수 없습니다.")
        }

        val existing = savedQuestionRepository.findTopByUser_IdAndQuestion_IdOrderByCreatedAtDesc(principal.userId, question.id)
        if (existing != null) {
            val nextNote = request.note?.trim()
            if (!nextNote.isNullOrBlank()) {
                existing.note = nextNote
            }
            return toSavedQuestionResponse(existing)
        }

        val saved = try {
            savedQuestionRepository.save(
                SavedQuestion(
                    user = actor,
                    question = question,
                    documentQuestion = null,
                    sourceTurn = null,
                    questionTextSnapshot = question.questionText,
                    categorySnapshot = question.category.name,
                    jobSnapshot = question.jobName ?: question.category.parent?.name?.trim(),
                    skillSnapshot = question.skillName ?: question.category.name.trim(),
                    category = question.category,
                    difficulty = question.difficulty.name,
                    sourceTag = when (question.sourceTag) {
                        QuestionSourceTag.SYSTEM -> TurnSourceTag.SYSTEM.name
                        QuestionSourceTag.USER -> TurnSourceTag.USER.name
                    },
                    tagsJson = question.tagsJson,
                    note = request.note?.trim()
                )
            )
        } catch (_: DataIntegrityViolationException) {
            savedQuestionRepository.findTopByUser_IdAndQuestion_IdOrderByCreatedAtDesc(principal.userId, question.id)
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "이미 저장된 질문입니다.")
        }
        return toSavedQuestionResponse(saved)
    }

    @Transactional(readOnly = true)
    fun getSavedQuestions(principal: AuthPrincipal): List<SavedQuestionResponse> {
        lateinit var responses: List<SavedQuestionResponse>
        val elapsedMs = measureTimeMillis {
            responses = savedQuestionRepository.findAllByUser_IdOrderByCreatedAtDesc(principal.userId)
                .distinctBy { savedQuestionDedupKey(it) }
                .map { toSavedQuestionResponse(it) }
        }
        logger.info(
            "saved question lookup timing userId={} resultCount={} elapsedMs={}",
            principal.userId,
            responses.size,
            elapsedMs
        )
        return responses
    }

    @Transactional
    fun deleteSavedQuestion(principal: AuthPrincipal, savedQuestionId: Long) {
        val saved = savedQuestionRepository.findByIdAndUser_Id(savedQuestionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 질문을 찾을 수 없습니다.")
        val sourceTurn = saved.sourceTurn
        if (sourceTurn != null) {
            sourceTurn.isBookmarked = false
            interviewTurnRepository.save(sourceTurn)
        }
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

    @Transactional(readOnly = true)
    fun getSessionResults(principal: AuthPrincipal, sessionId: Long): InterviewSessionResultsResponse {
        val session = interviewSessionRepository.findByIdAndUser_Id(sessionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "면접 세션을 찾을 수 없습니다.")
        val evaluationsByTurnId = interviewTurnEvaluationRepository
            .findAllByTurn_Session_Id(session.id)
            .associateBy { it.turn.id }

        lateinit var response: InterviewSessionResultsResponse
        val elapsedMs = measureTimeMillis {
            val turns = interviewTurnRepository.findAllDetailedBySessionIdOrderByTurnNoAsc(session.id)
                .map { turn ->
                    val evaluation = evaluationsByTurnId[turn.id]
                    InterviewTurnResultResponse(
                        turnId = turn.id,
                        turnNo = turn.turnNo,
                        questionId = turn.question?.id,
                        documentQuestionId = turn.documentQuestion?.id,
                        questionKind = if (turn.question != null) InterviewQuestionKind.TECH else InterviewQuestionKind.DOCUMENT,
                        categoryId = turn.category?.id,
                        questionText = turn.questionTextSnapshot,
                        answerText = turn.userAnswer,
                        category = turn.categorySnapshot,
                        difficulty = turn.difficulty,
                        sourceTag = normalizedTurnSourceTag(turn),
                        tags = parseTags(turn.tagsJson),
                        bookmarked = turn.isBookmarked,
                        evaluation = evaluation?.let {
                            val sessionLanguage = resolveInterviewLanguage(session.configJson)
                            val turnContext = parseTurnRagContext(objectMapper, turn.ragContextJson)
                            val resolved = resolveAnswerContent(
                                rawModelAnswer = turnContext.localizedModelAnswerFor(sessionLanguage)
                                    ?: turn.question?.canonicalAnswer
                                    ?: turn.documentQuestion?.referenceAnswer,
                                rawGuideText = it.bestPractice
                            )
                            TurnEvaluationResponse(
                                score = it.totalScore,
                                feedback = it.feedback,
                                bestPractice = if (session.mode == InterviewMode.QUESTION_SET_PRACTICE) "" else (resolved.guideText ?: it.bestPractice),
                                modelAnswer = resolved.modelAnswer,
                                providerUsed = when {
                                    it.model.equals("heuristic", ignoreCase = true) -> "HEURISTIC"
                                    it.model?.contains("gemini", ignoreCase = true) == true -> "GEMINI"
                                    it.model?.contains("nova", ignoreCase = true) == true ||
                                        it.model?.contains("bedrock", ignoreCase = true) == true -> "BEDROCK"
                                    else -> null
                                }
                            )
                        }
                    )
                }

            response = InterviewSessionResultsResponse(
                sessionId = session.id,
                status = session.status.name,
                mode = session.mode.name,
                finishedAt = session.finishedAt,
                turns = turns
            )
        }
        logger.info(
            "session results timing userId={} sessionId={} mode={} turnCount={} elapsedMs={}",
            principal.userId,
            session.id,
            session.mode.name,
            response.turns.size,
            elapsedMs
        )
        return response
    }

    @Transactional(readOnly = true)
    fun getTechSessionHistory(principal: AuthPrincipal): List<InterviewSessionHistoryResponse> {
        lateinit var responses: List<InterviewSessionHistoryResponse>
        val elapsedMs = measureTimeMillis {
            responses = interviewSessionRepository
                .findAllByUser_IdAndModeInOrderByCreatedAtDesc(principal.userId, listOf(InterviewMode.TECH))
                .filter { shouldStoreHistory(it.configJson) }
                .map { toSessionHistoryResponse(it) }
        }
        logger.info(
            "tech session history timing userId={} resultCount={} elapsedMs={}",
            principal.userId,
            responses.size,
            elapsedMs
        )
        return responses
    }

    @Transactional(readOnly = true)
    fun getTechSessionHistoryPage(
        principal: AuthPrincipal,
        page: Int,
        size: Int
    ): InterviewSessionHistoryPageResponse {
        validateHistoryPageRequest(page, size)
        lateinit var response: InterviewSessionHistoryPageResponse
        val elapsedMs = measureTimeMillis {
            val historySlice = collectTechHistoryPage(principal.userId, page, size)
            val filtered = historySlice.items
                .map { toSessionHistoryResponse(it) }

            response = InterviewSessionHistoryPageResponse(
                items = filtered,
                page = page,
                size = size,
                hasNext = historySlice.hasNext
            )
        }
        logger.info(
            "tech session history page timing userId={} page={} size={} itemCount={} elapsedMs={}",
            principal.userId,
            page,
            size,
            response.items.size,
            elapsedMs
        )
        return response
    }

    @Transactional(readOnly = true)
    fun getTechSessionHistorySummary(principal: AuthPrincipal, sessionId: Long): InterviewSessionHistoryResponse {
        val session = interviewSessionRepository.findByIdAndUser_Id(sessionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "면접 세션을 찾을 수 없습니다.")
        if (session.mode != InterviewMode.TECH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술질문 연습 세션만 조회할 수 있습니다.")
        }
        if (!shouldStoreHistory(session.configJson)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "조회 가능한 기술질문 연습 이력이 없습니다.")
        }
        return toSessionHistoryResponse(session)
    }

    @Transactional(readOnly = true)
    fun getLatestIncompleteTechSession(
        principal: AuthPrincipal,
        sessionMode: String?
    ): ResumeInterviewSessionResponse? {
        val allowedModes = when (sessionMode?.trim()?.uppercase()) {
            InterviewMode.QUESTION_SET_PRACTICE.name -> listOf(InterviewMode.QUESTION_SET_PRACTICE)
            InterviewMode.TECH.name, null, "" -> listOf(InterviewMode.TECH)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 sessionMode 입니다.")
        }
        var response: ResumeInterviewSessionResponse? = null
        val elapsedMs = measureTimeMillis {
            val latestSession = interviewSessionRepository
                .findAllByUser_IdAndModeInOrderByUpdatedAtDescCreatedAtDesc(
                    userId = principal.userId,
                    modes = allowedModes
                )
                .firstOrNull()
            response = if (latestSession == null || latestSession.status != InterviewStatus.IN_PROGRESS) {
                null
            } else {
                toResumeSessionResponse(latestSession)
            }
        }
        logger.info(
            "latest tech session timing userId={} modes={} found={} elapsedMs={}",
            principal.userId,
            allowedModes.joinToString(",") { it.name },
            response != null,
            elapsedMs
        )
        return response
    }

    @Transactional
    fun dismissTechSession(principal: AuthPrincipal, sessionId: Long) {
        val session = interviewSessionRepository.findByIdAndUser_Id(sessionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "면접 세션을 찾을 수 없습니다.")
        if (session.mode != InterviewMode.TECH && session.mode != InterviewMode.QUESTION_SET_PRACTICE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술질문 세션만 종료할 수 있습니다.")
        }
        if (session.status != InterviewStatus.IN_PROGRESS) return
        session.status = InterviewStatus.DONE
        session.finishedAt = OffsetDateTime.now()
    }

    private fun resolveCandidates(
        principal: AuthPrincipal,
        request: StartTechInterviewRequest,
        resolvedCategoryId: Long?
    ): List<QaQuestion> {
        val filterCategoryIds = resolveCategoryIds(resolvedCategoryId ?: request.categoryId)
        if (request.setId != null) {
            val set = questionSetRepository.findByIdAndDeletedAtIsNull(request.setId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")
            val canAccess = set.ownerUser?.id == principal.userId ||
                set.visibility == QuestionSetVisibility.GLOBAL ||
                principal.role == com.cw.vlainter.domain.user.entity.UserRole.ADMIN
            if (!canAccess) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 질문 세트에 접근할 수 없습니다.")
            }
            if (set.status != QuestionSetStatus.ACTIVE) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 질문 세트는 연습에 사용할 수 없습니다.")
            }
            return questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(set.id)
                .map { it.question }
                .filter { matchesFilter(it, request, filterCategoryIds) }
                .filter { isAcceptableTechQuestion(it) }
        }

        return questionRepository.findCandidatesForUser(
            userId = principal.userId,
            setStatus = QuestionSetStatus.ACTIVE,
            globalVisibility = QuestionSetVisibility.GLOBAL,
            difficulty = request.difficulty,
            sourceTag = request.sourceTag
        ).let { questions ->
            val filtered = if (filterCategoryIds.isEmpty()) questions else questions.filter { it.category.id in filterCategoryIds }
            filtered.filter { isAcceptableTechQuestion(it) }
        }
    }

    private fun shouldReuseMatchingQuestions(
        request: StartTechInterviewRequest,
        techQuestionReusePolicy: TechQuestionReusePolicy
    ): Boolean {
        if (request.setId != null) return true
        if (!hasExplicitTechSelection(request)) return true
        return techQuestionReusePolicy == TechQuestionReusePolicy.REUSE_MATCHING
    }

    private fun hasExplicitTechSelection(request: StartTechInterviewRequest): Boolean {
        return request.categoryId != null ||
            !request.jobName.isNullOrBlank() ||
            !request.skillName.isNullOrBlank()
    }

    private fun generateCategoryQuestions(
        owner: com.cw.vlainter.domain.user.entity.User,
        request: StartTechInterviewRequest,
        context: InterviewCategoryContextResolver.ResolvedCategoryContext
    ): List<QaQuestion> {
        val category = context.category
        val jobName = context.jobName
        val skillName = context.skillName

        val generated = try {
            interviewAiOrchestrator.generateTechQuestions(
                jobName = jobName,
                skillName = skillName,
                difficulty = request.difficulty,
                questionCount = request.questionCount.coerceAtLeast(5),
                language = request.language
            )
        } catch (ex: GeminiTransientException) {
            throw toGeminiOverloadException(ex)
        }
        if (generated.isEmpty()) return emptyList()

        val setTitle = "$jobName / $skillName"
        val autoSet = questionSetRepository.findFirstByOwnerUser_IdAndOwnerTypeAndVisibilityAndJobNameAndSkillNameAndDescriptionAndDeletedAtIsNullOrderByCreatedAtDesc(
            userId = owner.id,
            ownerType = QuestionSetOwnerType.USER,
            visibility = QuestionSetVisibility.PRIVATE,
            jobName = jobName,
            skillName = skillName,
            description = AI_GENERATED_SET_DESCRIPTION
        )
            ?: questionSetRepository.save(
                QaQuestionSet(
                    ownerUser = owner,
                    ownerType = QuestionSetOwnerType.USER,
                    title = setTitle,
                    jobName = jobName,
                    skillName = skillName,
                    description = AI_GENERATED_SET_DESCRIPTION,
                    visibility = QuestionSetVisibility.PRIVATE,
                    status = QuestionSetStatus.ACTIVE
                )
            )

        val collected = mutableListOf<QaQuestion>()
        generated.forEach { item ->
            val fingerprint = fingerprintFor(item.questionText, categoryKey(category), (request.difficulty ?: QuestionDifficulty.MEDIUM).name)
            val question = questionRepository.findByFingerprintAndDeletedAtIsNull(fingerprint)
                ?: questionRepository.save(
                    QaQuestion(
                        fingerprint = fingerprint,
                        questionText = item.questionText.trim(),
                        canonicalAnswer = item.canonicalAnswer?.trim(),
                        category = category,
                        jobName = jobName,
                        skillName = skillName,
                        difficulty = request.difficulty ?: QuestionDifficulty.MEDIUM,
                        sourceTag = QuestionSourceTag.SYSTEM,
                        tagsJson = objectMapper.writeValueAsString(item.tags.distinct()),
                        createdBy = owner
                    )
                )
            if (question.sourceTag != QuestionSourceTag.SYSTEM) {
                question.sourceTag = QuestionSourceTag.SYSTEM
            }
            if (!questionSetItemRepository.existsBySet_IdAndQuestion_Id(autoSet.id, question.id)) {
                val nextOrder = questionSetItemRepository.findMaxOrderNo(autoSet.id) + 1
                questionSetItemRepository.save(
                    QaQuestionSetItem(
                        set = autoSet,
                        question = question,
                        orderNo = nextOrder
                    )
                )
            }
            collected += question
        }
        logger.info(
            "tech question generation persistence userId={} categoryId={} generatedCount={} persistedCount={} questionSetId={}",
            owner.id,
            category.id,
            generated.size,
            collected.size,
            autoSet.id
        )
        return collected
    }

    private fun toGeminiOverloadException(ex: GeminiTransientException): ResponseStatusException {
        val status = if (ex.statusCode == 429) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.SERVICE_UNAVAILABLE
        val providerLabel = when (ex.provider) {
            AiProvider.BEDROCK -> "Bedrock"
            AiProvider.GEMINI -> "Gemini"
        }
        return ResponseStatusException(
            status,
            "$providerLabel API 과부하로 요청을 처리할 수 없습니다. 1분 후 다시 시도해 주세요.",
            ex
        )
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

    private fun isAcceptableTechQuestion(question: QaQuestion): Boolean {
        val normalized = question.questionText.replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 18) return false
        // NOTE:
        // 과도한 하드 필터를 비활성화한다.
        // - raw enum/path 포함 즉시 탈락
        // - category 키워드 미포함 즉시 탈락
        // 품질 보정은 생성 프롬프트/평가 단계에서 처리한다.
        // if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED)\\b").containsMatchIn(normalized)) return false
        // val focusTokens = question.category.name
        //     .lowercase()
        //     .split(Regex("[^a-z0-9가-힣]+"))
        //     .filter { it.length >= 2 }
        //     .toSet()
        // if (focusTokens.isNotEmpty()) {
        //     val lowered = normalized.lowercase()
        //     if (focusTokens.none { lowered.contains(it) }) return false
        // }
        return true
    }

    private fun toTurnSource(question: QaQuestion): TurnSourceTag {
        if (questionSetItemRepository.existsInAiGeneratedSetByQuestionId(question.id)) {
            return TurnSourceTag.SYSTEM
        }
        return when (question.sourceTag) {
            QuestionSourceTag.SYSTEM -> TurnSourceTag.SYSTEM
            QuestionSourceTag.USER -> TurnSourceTag.USER
        }
    }

    private fun createTurnFromRef(session: InterviewSession, turnNo: Int, ref: QuestionRef): InterviewTurn {
        val language = resolveInterviewLanguage(session.configJson)
        val turn = when (ref.kind) {
            InterviewQuestionKind.TECH -> {
            val question = questionRepository.findByIdAndDeletedAtIsNull(ref.id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다: ${ref.id}")
            val storedLocalized = findSessionLocalizedQueueContent(objectMapper, session.configJson, language, ref.kind, ref.id)
            val localized = storedLocalized?.let {
                com.cw.vlainter.domain.interview.ai.LocalizedInterviewContent(
                    questionText = it.questionText ?: question.questionText,
                    modelAnswer = it.modelAnswer,
                    evidence = it.evidence
                )
            } ?: localizeTurnContentIfNeeded(
                userId = session.user.id,
                language = language,
                questionText = question.questionText,
                modelAnswer = question.canonicalAnswer,
                evidence = emptyList()
            )
            InterviewTurn(
                session = session,
                turnNo = turnNo,
                sourceTag = toTurnSource(question),
                question = question,
                questionTextSnapshot = localized?.questionText ?: question.questionText,
                categorySnapshot = question.category.name,
                jobSnapshot = question.jobName ?: question.category.parent?.name?.trim(),
                skillSnapshot = question.skillName ?: question.category.name.trim(),
                category = question.category,
                difficulty = question.difficulty.name,
                tagsJson = question.tagsJson
                ,
                ragContextJson = buildTurnRagContextJson(
                    objectMapper = objectMapper,
                    evidence = emptyList(),
                    language = language,
                    localized = localized?.let {
                        StoredLocalizedTurnContent(
                            questionText = it.questionText,
                            modelAnswer = it.modelAnswer,
                            evidence = it.evidence
                        )
                    }
                )
            )
        }

        InterviewQuestionKind.DOCUMENT -> {
            val question = documentQuestionRepository.findByIdAndUserId(ref.id, session.user.id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문서 질문을 찾을 수 없습니다: ${ref.id}")
            val evidence = runCatching { objectMapper.readValue(question.evidenceJson, Array<String>::class.java).toList() }
                .getOrDefault(emptyList())
            val storedLocalized = findSessionLocalizedQueueContent(objectMapper, session.configJson, language, ref.kind, ref.id)
            val localized = storedLocalized?.let {
                com.cw.vlainter.domain.interview.ai.LocalizedInterviewContent(
                    questionText = it.questionText ?: question.questionText,
                    modelAnswer = it.modelAnswer ?: question.referenceAnswer,
                    evidence = it.evidence.ifEmpty { evidence }
                )
            } ?: localizeTurnContentIfNeeded(
                userId = session.user.id,
                language = language,
                questionText = question.questionText,
                modelAnswer = question.referenceAnswer,
                evidence = evidence
            )
            InterviewTurn(
                session = session,
                turnNo = turnNo,
                sourceTag = TurnSourceTag.DOC_RAG,
                documentQuestion = question,
                questionTextSnapshot = localized?.questionText ?: question.questionText,
                categorySnapshot = question.questionType,
                difficulty = question.difficulty,
                tagsJson = "[]",
                ragContextJson = buildTurnRagContextJson(
                    objectMapper = objectMapper,
                    evidence = evidence,
                    language = language,
                    localized = localized?.let {
                        StoredLocalizedTurnContent(
                            questionText = it.questionText,
                            modelAnswer = it.modelAnswer,
                            evidence = it.evidence
                        )
                    }
                )
            )
        }

            InterviewQuestionKind.INTRO -> {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "자기소개 문항은 실전 모의면접에서만 사용할 수 있습니다.")
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
            sourceTag = normalizedTurnSourceTag(turn),
            category = turn.categorySnapshot,
            difficulty = turn.difficulty,
            tags = parseTags(turn.tagsJson)
        )
    }

    private fun toSavedQuestionResponse(saved: SavedQuestion): SavedQuestionResponse {
        val evaluation = saved.sourceTurn?.id?.let { interviewTurnEvaluationRepository.findByTurn_Id(it) }
        val sessionLanguage = saved.sourceTurn?.session?.let { resolveInterviewLanguage(it.configJson) } ?: InterviewLanguage.KO
        val turnContext = saved.sourceTurn?.let { parseTurnRagContext(objectMapper, it.ragContextJson) } ?: TurnRagContext()
        val resolved = resolveAnswerContent(
            rawModelAnswer = turnContext.localizedModelAnswerFor(sessionLanguage)
                ?: saved.question?.canonicalAnswer
                ?: saved.documentQuestion?.referenceAnswer,
            rawGuideText = evaluation?.bestPractice
        )
        return SavedQuestionResponse(
            savedQuestionId = saved.id,
            questionId = saved.question?.id,
            documentQuestionId = saved.documentQuestion?.id,
            questionKind = if (saved.question != null) InterviewQuestionKind.TECH else InterviewQuestionKind.DOCUMENT,
            categoryId = saved.category?.id,
            questionText = saved.questionTextSnapshot,
            canonicalAnswer = resolved.modelAnswer,
            modelAnswer = resolved.modelAnswer,
            bestPractice = null,
            feedback = evaluation?.feedback,
            answerText = saved.sourceTurn?.userAnswer,
            branchName = saved.category?.parent?.parent?.name?.trim(),
            jobName = saved.question?.jobName?.trim()
                ?: saved.category?.parent?.name?.trim()
                ?: saved.jobSnapshot,
            skillName = saved.question?.skillName?.trim()
                ?: saved.category?.name?.trim()
                ?: saved.skillSnapshot,
            category = saved.categorySnapshot,
            difficulty = saved.difficulty,
            sourceTag = normalizeSavedSourceTag(saved),
            tags = parseTags(saved.tagsJson),
            note = saved.note,
            createdAt = saved.createdAt
        )
    }

    private fun localizeTurnContentIfNeeded(
        userId: Long,
        language: InterviewLanguage,
        questionText: String,
        modelAnswer: String?,
        evidence: List<String>
    ): com.cw.vlainter.domain.interview.ai.LocalizedInterviewContent? {
        if (language != InterviewLanguage.EN) return null
        return runCatching {
            userGeminiApiKeyService.withUserApiKey(userId) {
                interviewAiOrchestrator.localizeTurnContent(
                    questionText = questionText,
                    modelAnswer = modelAnswer,
                    evidence = evidence,
                    language = language
                )
            }
        }.onFailure { ex ->
            logger.warn("turn content localization skipped userId={} language={} reason={}", userId, language, ex::class.simpleName, ex)
        }.getOrNull()
    }

    private fun buildLocalizedTechQueueEntries(
        userId: Long,
        language: InterviewLanguage,
        questions: List<QaQuestion>
    ): List<Map<String, Any?>> {
        if (language != InterviewLanguage.EN || questions.isEmpty()) return emptyList()
        val localized = runCatching {
            userGeminiApiKeyService.withUserApiKey(userId) {
                interviewAiOrchestrator.localizeTurnContents(
                    questions.map { question ->
                        com.cw.vlainter.domain.interview.ai.TurnContentLocalizationRequest(
                            key = question.id.toString(),
                            questionText = question.questionText,
                            modelAnswer = question.canonicalAnswer,
                            evidence = emptyList()
                        )
                    },
                    language
                )
            }
        }.onFailure { ex ->
            logger.warn("tech queue localization skipped userId={} language={} reason={}", userId, language, ex::class.simpleName, ex)
        }.getOrNull() ?: return emptyList()
        return buildSessionLocalizedQueueEntries(
            kind = InterviewQuestionKind.TECH,
            entries = localized.entries.associate { (key, value) ->
                key.toLong() to StoredLocalizedTurnContent(
                    questionText = value.questionText,
                    modelAnswer = value.modelAnswer,
                    evidence = value.evidence
                )
            }
        )
    }

    private fun normalizedTurnSourceTag(turn: InterviewTurn): TurnSourceTag {
        if (turn.sourceTag == TurnSourceTag.USER && turn.question?.id?.let { questionSetItemRepository.existsInAiGeneratedSetByQuestionId(it) } == true) {
            return TurnSourceTag.SYSTEM
        }
        return turn.sourceTag
    }

    private fun normalizeSavedSourceTag(saved: SavedQuestion): String? {
        val current = saved.sourceTag ?: return null
        if (current != TurnSourceTag.USER.name) return current
        if (saved.question?.id?.let { questionSetItemRepository.existsInAiGeneratedSetByQuestionId(it) } == true) {
            return TurnSourceTag.SYSTEM.name
        }
        return current
    }

    private fun resolveExistingSavedQuestion(userId: Long, questionId: Long?, documentQuestionId: Long?): SavedQuestion? {
        if (questionId != null) {
            return savedQuestionRepository.findTopByUser_IdAndQuestion_IdOrderByCreatedAtDesc(userId, questionId)
        }
        if (documentQuestionId != null) {
            return savedQuestionRepository.findTopByUser_IdAndDocumentQuestion_IdOrderByCreatedAtDesc(userId, documentQuestionId)
        }
        return null
    }

    private fun savedQuestionDedupKey(saved: SavedQuestion): String {
        saved.question?.id?.let { return "tech:$it" }
        saved.documentQuestion?.id?.let { return "document:$it" }
        return "saved:${saved.id}"
    }

    private fun fingerprintFor(questionText: String, category: String, difficulty: String): String {
        val normalized = "${questionText.trim().lowercase()}|${category.trim().lowercase()}|${difficulty.trim().lowercase()}"
            .replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(raw, object : TypeReference<List<String>>() {})
        }.getOrDefault(emptyList())
    }

    private fun categoryKey(category: com.cw.vlainter.domain.interview.entity.QaCategory): String {
        val job = category.parent?.name?.trim().orEmpty()
        return "$job/${category.name.trim()}".trim()
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

    private fun loadUserForUpdate(userId: Long) = userRepository.findByIdForUpdate(userId)
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자 정보를 찾을 수 없습니다.")

    private fun toSessionConfigJson(
        questionRefs: List<QuestionRef>,
        cursor: Int,
        meta: Map<String, Any?> = emptyMap()
    ): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "queue" to questionRefs,
                "cursor" to cursor,
                "meta" to meta
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
        val meta = node["meta"]
            ?.takeIf { it.isObject }
            ?.properties()
            ?.asSequence()
            ?.associate { (key, value) ->
                key to when {
                    value.isNull -> null
                    value.isBoolean -> value.asBoolean()
                    value.isNumber -> value.numberValue()
                    value.isTextual -> value.asText()
                    else -> value
                }
            }
            ?: emptyMap()
        return SessionConfig(queue = queue, cursor = cursor, meta = meta)
    }

    data class QuestionRef(
        val kind: InterviewQuestionKind,
        val id: Long
    )

    private data class SessionConfig(
        val queue: List<QuestionRef> = emptyList(),
        val cursor: Int = 0,
        val meta: Map<String, Any?> = emptyMap()
    )

    private data class ParsedSessionMeta(
        val meta: JsonNode?,
        val queueSize: Int,
        val selectedDocuments: List<InterviewHistoryDocumentResponse>
    )

    private fun shouldStoreHistory(configJson: String?): Boolean {
        if (configJson.isNullOrBlank()) return true
        val root = runCatching { objectMapper.readTree(configJson) }.getOrNull() ?: return true
        val saveHistoryNode = root.path("meta").path("saveHistory")
        return !saveHistoryNode.isBoolean || saveHistoryNode.asBoolean()
    }

    private fun toSessionHistoryResponse(session: InterviewSession): InterviewSessionHistoryResponse {
        val parsed = parseSessionMeta(session)
        val meta = parsed.meta
        val turns = interviewTurnRepository.findAllBySession_IdOrderByTurnNoAsc(session.id)

        return InterviewSessionHistoryResponse(
            sessionId = session.id,
            status = session.status.name,
            mode = session.mode.name,
            language = meta?.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: InterviewLanguage.KO.name,
            questionCount = meta?.get("questionCount")?.asInt() ?: max(parsed.queueSize, turns.size),
            difficulty = meta?.get("difficulty")?.asText() ?: turns.firstOrNull()?.difficulty,
            difficultyRating = meta?.get("difficultyRating")?.asInt()
                ?: difficultyToRating(turns.firstOrNull()?.difficulty?.let { runCatching { QuestionDifficulty.valueOf(it) }.getOrNull() }),
            categoryId = meta?.get("categoryId")?.takeIf { it.canConvertToLong() }?.asLong()
                ?: turns.firstOrNull()?.category?.id,
            categoryName = meta?.get("categoryName")?.asText()?.takeIf { it.isNotBlank() }
                ?: turns.firstOrNull()?.categorySnapshot,
            jobName = meta?.get("jobName")?.asText()?.takeIf { it.isNotBlank() },
            selectedDocuments = parsed.selectedDocuments,
            startedAt = session.startedAt,
            finishedAt = session.finishedAt
        )
    }

    private fun validateHistoryPageRequest(page: Int, size: Int) {
        HistoryPageRequestValidator.validate(page, size)
    }

    private fun collectTechHistoryPage(userId: Long, page: Int, size: Int): TechHistorySlice {
        var remainingToSkip = page * size
        var sourcePage = 0
        val collected = mutableListOf<InterviewSession>()
        var scannedSessionCount = 0

        while (true) {
            val slice = interviewSessionRepository.findAllByUser_IdAndModeInOrderByCreatedAtDesc(
                userId,
                listOf(InterviewMode.TECH),
                org.springframework.data.domain.PageRequest.of(sourcePage, TECH_HISTORY_SCAN_BATCH_SIZE)
            )
            scannedSessionCount += slice.content.size
            val visibleSessions = slice.content.filter { shouldStoreHistory(it.configJson) }

            if (remainingToSkip >= visibleSessions.size) {
                remainingToSkip -= visibleSessions.size
            } else {
                val startIndex = remainingToSkip.coerceAtLeast(0)
                collected += visibleSessions.drop(startIndex)
                remainingToSkip = 0
            }

            if (remainingToSkip == 0 && collected.size > size) {
                break
            }
            if (!slice.hasNext()) {
                break
            }
            sourcePage += 1
        }

        logger.info(
            "tech history slice scan userId={} page={} size={} scannedSessions={} collectedSessions={} sourcePages={}",
            userId,
            page,
            size,
            scannedSessionCount,
            collected.size,
            sourcePage + 1
        )
        return TechHistorySlice(
            items = collected.take(size),
            hasNext = collected.size > size
        )
    }

    private fun toResumeSessionResponse(session: InterviewSession): ResumeInterviewSessionResponse? {
        val currentTurn = interviewTurnRepository.findFirstBySession_IdAndUserAnswerIsNullOrderByTurnNoAsc(session.id)
            ?: return null
        val parsed = parseSessionMeta(session)
        val meta = parsed.meta
        val turns = interviewTurnRepository.findAllBySession_IdOrderByTurnNoAsc(session.id)
        return ResumeInterviewSessionResponse(
            sessionId = session.id,
            status = session.status.name,
            mode = session.mode.name,
            language = meta?.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: InterviewLanguage.KO.name,
            currentQuestion = toInterviewQuestionResponse(currentTurn),
            questionCount = meta?.get("questionCount")?.asInt() ?: max(parsed.queueSize, turns.size),
            difficulty = meta?.get("difficulty")?.asText() ?: turns.firstOrNull()?.difficulty,
            difficultyRating = meta?.get("difficultyRating")?.asInt()
                ?: difficultyToRating(turns.firstOrNull()?.difficulty?.let { runCatching { QuestionDifficulty.valueOf(it) }.getOrNull() }),
            categoryId = meta?.get("categoryId")?.takeIf { it.canConvertToLong() }?.asLong()
                ?: turns.firstOrNull()?.category?.id,
            categoryName = meta?.get("categoryName")?.asText()?.takeIf { it.isNotBlank() }
                ?: turns.firstOrNull()?.categorySnapshot,
            jobName = meta?.get("jobName")?.asText()?.takeIf { it.isNotBlank() },
            selectedDocuments = parsed.selectedDocuments,
            questionSetId = meta?.get("questionSetId")?.takeIf { it.canConvertToLong() }?.asLong()
                ?: session.questionSet?.id,
            includeSelfIntroduction = meta?.get("includeSelfIntroduction")?.asBoolean() == true,
            providerUsed = meta?.get("providerUsed")?.asText()?.takeIf { it.isNotBlank() },
            fallbackDepth = meta?.get("fallbackDepth")?.asInt() ?: 0
        )
    }

    private data class TechHistorySlice(
        val items: List<InterviewSession>,
        val hasNext: Boolean
    )

    private fun parseSessionMeta(session: InterviewSession): ParsedSessionMeta {
        val root = runCatching { objectMapper.readTree(session.configJson) }.getOrNull()
        val meta = root?.get("meta")
        val queueSize = root?.get("queue")?.takeIf { it.isArray }?.size() ?: 0
        val selectedDocumentsNode = meta?.get("selectedDocuments")
        val selectedDocuments = when {
            selectedDocumentsNode == null -> emptyList()
            selectedDocumentsNode.isArray -> selectedDocumentsNode
                .mapNotNull { item -> item.toInterviewHistoryDocumentResponse() }
            selectedDocumentsNode.isObject -> selectedDocumentsNode
                .fieldNames()
                .asSequence()
                .mapNotNull { fieldName -> selectedDocumentsNode.get(fieldName)?.toInterviewHistoryDocumentResponse() }
                .toList()
            else -> emptyList()
        }
        return ParsedSessionMeta(meta = meta, queueSize = queueSize, selectedDocuments = selectedDocuments)
    }

    private fun resolveInterviewLanguage(configJson: String?): InterviewLanguage {
        if (configJson.isNullOrBlank()) return InterviewLanguage.KO
        val root = runCatching { objectMapper.readTree(configJson) }.getOrNull() ?: return InterviewLanguage.KO
        val raw = root.path("meta").path("language").asText().trim().uppercase()
        return runCatching { InterviewLanguage.valueOf(raw) }.getOrDefault(InterviewLanguage.KO)
    }

    private fun assertInterviewAnswerLanguage(session: InterviewSession, answer: String) {
        if (resolveInterviewLanguage(session.configJson) != InterviewLanguage.EN) return
        val englishLetters = ENGLISH_LETTER_REGEX.findAll(answer).count()
        val hangulLetters = HANGUL_REGEX.findAll(answer).count()
        val englishWords = ENGLISH_WORD_REGEX.findAll(answer).count()
        val looksEnglishEnough = when {
            englishLetters < 2 || englishWords == 0 -> false
            hangulLetters == 0 -> true
            hangulLetters > englishLetters -> false
            else -> true
        }
        if (!looksEnglishEnough) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "영어 면접에서는 영어 답변으로 작성해 주세요.")
        }
    }

    private fun JsonNode.toInterviewHistoryDocumentResponse(): InterviewHistoryDocumentResponse? {
        val label = this["label"]?.asText()?.trim().orEmpty()
        if (label.isBlank()) return null
        return InterviewHistoryDocumentResponse(
            fileId = this["fileId"]?.takeIf { it.canConvertToLong() }?.asLong(),
            fileType = this["fileType"]?.asText(),
            label = label,
            ocrUsed = this["ocrUsed"]?.asBoolean() == true
        )
    }

    private fun difficultyToRating(difficulty: QuestionDifficulty?): Int? {
        return when (difficulty) {
            QuestionDifficulty.EASY -> 1
            QuestionDifficulty.MEDIUM -> 3
            QuestionDifficulty.HARD -> 5
            null -> null
        }
    }
}
