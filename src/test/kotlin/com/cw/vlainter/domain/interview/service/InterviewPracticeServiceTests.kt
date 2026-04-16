@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.AiRoutingContextHolder
import com.cw.vlainter.domain.interview.ai.GeneratedTechQuestion
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.StartTechInterviewRequest
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.TechQuestionReusePolicy
import com.cw.vlainter.domain.interview.repository.DocumentQuestionRepository
import com.cw.vlainter.domain.interview.repository.InterviewSessionRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnEvaluationRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.interview.repository.SavedQuestionRepository
import com.cw.vlainter.domain.interview.repository.UserQuestionAttemptRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.mockito.Mockito.doAnswer
import org.mockito.junit.jupiter.MockitoExtension
import jakarta.persistence.EntityManager
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class InterviewPracticeServiceTests {

    @org.mockito.Mock private lateinit var interviewAiOrchestrator: InterviewAiOrchestrator
    @org.mockito.Mock private lateinit var interviewEvaluationService: InterviewEvaluationService
    @org.mockito.Mock private lateinit var jobSkillCatalogService: JobSkillCatalogService
    @org.mockito.Mock private lateinit var categoryRepository: QaCategoryRepository
    @org.mockito.Mock private lateinit var categoryContextResolver: InterviewCategoryContextResolver
    @org.mockito.Mock private lateinit var questionRepository: QaQuestionRepository
    @org.mockito.Mock private lateinit var questionSetRepository: QaQuestionSetRepository
    @org.mockito.Mock private lateinit var questionSetItemRepository: QaQuestionSetItemRepository
    @org.mockito.Mock private lateinit var interviewSessionRepository: InterviewSessionRepository
    @org.mockito.Mock private lateinit var interviewTurnRepository: InterviewTurnRepository
    @org.mockito.Mock private lateinit var interviewTurnEvaluationRepository: InterviewTurnEvaluationRepository
    @org.mockito.Mock private lateinit var documentQuestionRepository: DocumentQuestionRepository
    @org.mockito.Mock private lateinit var userQuestionAttemptRepository: UserQuestionAttemptRepository
    @org.mockito.Mock private lateinit var savedQuestionRepository: SavedQuestionRepository
    @org.mockito.Mock private lateinit var userRepository: UserRepository
    @org.mockito.Mock private lateinit var userGeminiApiKeyService: UserGeminiApiKeyService
    @org.mockito.Mock private lateinit var adminInterviewSettingsService: AdminInterviewSettingsService
    @org.mockito.Mock private lateinit var entityManager: EntityManager

    private val objectMapper = ObjectMapper()
    private val aiRoutingContextHolder = AiRoutingContextHolder()

    @Test
    fun `같은 조건 질문 재사용 정책이면 기존 후보를 사용한다`() {
        val user = createUser()
        val category = createCategory()
        val question = createQuestion(id = 101L, category = category)
        val request = StartTechInterviewRequest(
            categoryId = category.id,
            difficulty = QuestionDifficulty.MEDIUM,
            questionCount = 1
        )

        given(userRepository.findById(1L)).willReturn(Optional.of(user))
        given(adminInterviewSettingsService.getTechQuestionReusePolicy()).willReturn(TechQuestionReusePolicy.REUSE_MATCHING)
        given(categoryRepository.findByIdAndDeletedAtIsNull(category.id)).willReturn(category)
        given(categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc("${category.path}/"))
            .willReturn(emptyList())
        given(
            questionRepository.findCandidatesForUser(
                user.id,
                com.cw.vlainter.domain.interview.entity.QuestionSetStatus.ACTIVE,
                QuestionSetVisibility.GLOBAL,
                request.difficulty,
                request.sourceTag
            )
        ).willReturn(listOf(question))
        given(questionRepository.findByIdAndDeletedAtIsNull(101L)).willReturn(question)
        given(interviewSessionRepository.save(any(InterviewSession::class.java))).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewSession>(0)
            InterviewSession(
                id = 501L,
                user = source.user,
                mode = source.mode,
                status = source.status,
                questionSet = source.questionSet,
                revealPolicy = source.revealPolicy,
                configJson = source.configJson
            )
        }
        given(interviewTurnRepository.save(any(InterviewTurn::class.java))).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewTurn>(0)
            InterviewTurn(
                id = 901L,
                session = source.session,
                turnNo = source.turnNo,
                sourceTag = source.sourceTag,
                question = source.question,
                documentQuestion = source.documentQuestion,
                questionTextSnapshot = source.questionTextSnapshot,
                categorySnapshot = source.categorySnapshot,
                jobSnapshot = source.jobSnapshot,
                skillSnapshot = source.skillSnapshot,
                category = source.category,
                difficulty = source.difficulty,
                tagsJson = source.tagsJson,
                ragContextJson = source.ragContextJson
            )
        }
        given(questionSetItemRepository.existsInAiGeneratedSetByQuestionId(question.id)).willReturn(false)

        val result = service().startTechInterview(
            principal = AuthPrincipal(1L, "user@vlainter.com", "S", UserRole.USER),
            request = request
        )

        assertThat(result.currentQuestion.questionId).isEqualTo(question.id)
        then(questionRepository).should().findCandidatesForUser(
            user.id,
            com.cw.vlainter.domain.interview.entity.QuestionSetStatus.ACTIVE,
            QuestionSetVisibility.GLOBAL,
            request.difficulty,
            request.sourceTag
        )
        then(interviewAiOrchestrator).shouldHaveNoInteractions()
    }

    @Test
    fun `무조건 생성 정책이면 기존 후보가 있어도 새 질문 생성을 시도한다`() {
        val user = createUser()
        val branch = createCategory(id = 11L, name = "개발", depth = 0, path = "/dev")
        val job = createCategory(id = 12L, name = "백엔드개발자", depth = 1, path = "/dev/backend", parent = branch)
        val skill = createCategory(id = 13L, name = "Spring", depth = 2, path = "/dev/backend/spring", parent = job)
        val generatedQuestion = createQuestion(id = 202L, category = skill, text = "Spring Bean 생명주기를 설명해 주세요.")

        given(userRepository.findById(1L)).willReturn(Optional.of(user))
        given(adminInterviewSettingsService.getTechQuestionReusePolicy()).willReturn(TechQuestionReusePolicy.ALWAYS_GENERATE)
        given(
            categoryContextResolver.resolve(
                categoryId = skill.id,
                jobName = null,
                skillName = null,
                requireIfMissing = false
            )
        ).willReturn(
            InterviewCategoryContextResolver.ResolvedCategoryContext(
                category = skill,
                branchName = "개발",
                jobName = "백엔드개발자",
                skillName = "Spring"
            )
        )
        given(
            interviewAiOrchestrator.generateTechQuestions(
                jobName = "백엔드개발자",
                skillName = "Spring",
                difficulty = QuestionDifficulty.MEDIUM,
                questionCount = 5,
                language = InterviewLanguage.KO
            )
        ).willReturn(
            listOf(
                GeneratedTechQuestion(
                    "Spring Bean 생명주기를 설명해 주세요.",
                    "컨테이너 초기화부터 destroy까지 답합니다.",
                    listOf("Spring")
                )
            )
        )
        doAnswer { invocation ->
            val block = invocation.getArgument<() -> List<QaQuestion>>(1)
            block()
        }.`when`(userGeminiApiKeyService).withUserApiKey(
            eq(1L),
            anyNonNull<() -> List<QaQuestion>>()
        )
        given(
            questionSetRepository.findFirstByOwnerUser_IdAndOwnerTypeAndVisibilityAndJobNameAndSkillNameAndDescriptionAndDeletedAtIsNullOrderByCreatedAtDesc(
                user.id,
                QuestionSetOwnerType.USER,
                QuestionSetVisibility.PRIVATE,
                "백엔드개발자",
                "Spring",
                "카테고리 기반 자동 생성 문답"
            )
        )
            .willReturn(null)
        given(questionSetRepository.save(anyNonNull<QaQuestionSet>())).willAnswer { invocation ->
            val source = invocation.getArgument<QaQuestionSet>(0)
            QaQuestionSet(
                id = 301L,
                ownerUser = source.ownerUser,
                ownerType = source.ownerType,
                title = source.title,
                jobName = source.jobName,
                skillName = source.skillName,
                description = source.description,
                visibility = source.visibility,
                status = source.status
            )
        }
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyNonNull())).willReturn(generatedQuestion)
        given(questionRepository.findByIdAndDeletedAtIsNull(202L)).willReturn(generatedQuestion)
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(301L, 202L)).willReturn(false)
        given(questionSetItemRepository.findMaxOrderNo(301L)).willReturn(0)
        given(questionSetItemRepository.save(anyNonNull<QaQuestionSetItem>())).willAnswer { it.getArgument(0) }
        given(interviewSessionRepository.save(anyNonNull<InterviewSession>())).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewSession>(0)
            InterviewSession(
                id = 502L,
                user = source.user,
                mode = source.mode,
                status = source.status,
                questionSet = source.questionSet,
                revealPolicy = source.revealPolicy,
                configJson = source.configJson
            )
        }
        given(interviewTurnRepository.save(anyNonNull<InterviewTurn>())).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewTurn>(0)
            InterviewTurn(
                id = 902L,
                session = source.session,
                turnNo = source.turnNo,
                sourceTag = source.sourceTag,
                question = source.question,
                documentQuestion = source.documentQuestion,
                questionTextSnapshot = source.questionTextSnapshot,
                categorySnapshot = source.categorySnapshot,
                jobSnapshot = source.jobSnapshot,
                skillSnapshot = source.skillSnapshot,
                category = source.category,
                difficulty = source.difficulty,
                tagsJson = source.tagsJson,
                ragContextJson = source.ragContextJson
            )
        }
        given(questionSetItemRepository.existsInAiGeneratedSetByQuestionId(generatedQuestion.id)).willReturn(true)

        val result = service().startTechInterview(
            principal = AuthPrincipal(1L, "user@vlainter.com", "S", UserRole.USER),
            request = StartTechInterviewRequest(
                categoryId = skill.id,
                difficulty = QuestionDifficulty.MEDIUM,
                questionCount = 1
            )
        )

        assertThat(result.currentQuestion.questionId).isEqualTo(generatedQuestion.id)
        then(questionRepository).should(never()).findCandidatesForUser(
            user.id,
            com.cw.vlainter.domain.interview.entity.QuestionSetStatus.ACTIVE,
            QuestionSetVisibility.GLOBAL,
            QuestionDifficulty.MEDIUM,
            null
        )
        then(interviewAiOrchestrator).should().generateTechQuestions(
            "백엔드개발자",
            "Spring",
            QuestionDifficulty.MEDIUM,
            5,
            InterviewLanguage.KO
        )
    }

    @Test
    fun `무조건 생성 정책이어도 기술 선택값이 없으면 기존 후보를 재사용한다`() {
        val user = createUser()
        val category = createCategory()
        val question = createQuestion(id = 303L, category = category, text = "기존 질문을 그대로 재사용하는 흐름을 검증합니다.")
        val request = StartTechInterviewRequest(
            categoryId = null,
            jobName = null,
            skillName = null,
            difficulty = QuestionDifficulty.MEDIUM,
            questionCount = 1
        )

        given(userRepository.findById(1L)).willReturn(Optional.of(user))
        given(adminInterviewSettingsService.getTechQuestionReusePolicy()).willReturn(TechQuestionReusePolicy.ALWAYS_GENERATE)
        given(
            questionRepository.findCandidatesForUser(
                user.id,
                com.cw.vlainter.domain.interview.entity.QuestionSetStatus.ACTIVE,
                QuestionSetVisibility.GLOBAL,
                request.difficulty,
                request.sourceTag
            )
        ).willReturn(listOf(question))
        given(questionRepository.findByIdAndDeletedAtIsNull(question.id)).willReturn(question)
        given(interviewSessionRepository.save(any(InterviewSession::class.java))).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewSession>(0)
            InterviewSession(
                id = 503L,
                user = source.user,
                mode = source.mode,
                status = source.status,
                questionSet = source.questionSet,
                revealPolicy = source.revealPolicy,
                configJson = source.configJson
            )
        }
        given(interviewTurnRepository.save(any(InterviewTurn::class.java))).willAnswer { invocation ->
            val source = invocation.getArgument<InterviewTurn>(0)
            InterviewTurn(
                id = 903L,
                session = source.session,
                turnNo = source.turnNo,
                sourceTag = source.sourceTag,
                question = source.question,
                documentQuestion = source.documentQuestion,
                questionTextSnapshot = source.questionTextSnapshot,
                categorySnapshot = source.categorySnapshot,
                jobSnapshot = source.jobSnapshot,
                skillSnapshot = source.skillSnapshot,
                category = source.category,
                difficulty = source.difficulty,
                tagsJson = source.tagsJson,
                ragContextJson = source.ragContextJson
            )
        }
        given(questionSetItemRepository.existsInAiGeneratedSetByQuestionId(question.id)).willReturn(false)

        val result = service().startTechInterview(
            principal = AuthPrincipal(1L, "user@vlainter.com", "S", UserRole.USER),
            request = request
        )

        assertThat(result.currentQuestion.questionId).isEqualTo(question.id)
        then(interviewAiOrchestrator).shouldHaveNoInteractions()
    }

    @Test
    fun `getSessionResults loads evaluations in one batch`() {
        val user = createUser()
        val category = createCategory()
        val question1 = createQuestion(id = 201L, category = category, text = "Spring DI를 설명해 주세요.")
        val question2 = createQuestion(id = 202L, category = category, text = "Bean Scope를 설명해 주세요.")
        val session = InterviewSession(
            id = 501L,
            user = user,
            mode = com.cw.vlainter.domain.interview.entity.InterviewMode.TECH,
            status = com.cw.vlainter.domain.interview.entity.InterviewStatus.DONE,
            revealPolicy = com.cw.vlainter.domain.interview.entity.RevealPolicy.PER_TURN,
            configJson = """{"meta":{"language":"KO"}}""",
            finishedAt = java.time.OffsetDateTime.now()
        )
        val turn1 = InterviewTurn(
            id = 701L,
            session = session,
            turnNo = 1,
            sourceTag = com.cw.vlainter.domain.interview.entity.TurnSourceTag.SYSTEM,
            question = question1,
            questionTextSnapshot = question1.questionText,
            categorySnapshot = question1.category.name,
            jobSnapshot = question1.jobName,
            skillSnapshot = question1.skillName,
            category = question1.category,
            difficulty = question1.difficulty.name,
            tagsJson = question1.tagsJson
        )
        val turn2 = InterviewTurn(
            id = 702L,
            session = session,
            turnNo = 2,
            sourceTag = com.cw.vlainter.domain.interview.entity.TurnSourceTag.SYSTEM,
            question = question2,
            questionTextSnapshot = question2.questionText,
            categorySnapshot = question2.category.name,
            jobSnapshot = question2.jobName,
            skillSnapshot = question2.skillName,
            category = question2.category,
            difficulty = question2.difficulty.name,
            tagsJson = question2.tagsJson
        )
        val evaluation1 = com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation(
            id = 801L,
            turn = turn1,
            totalScore = java.math.BigDecimal("8.5"),
            feedback = "좋습니다.",
            bestPractice = "핵심을 잘 짚었습니다.",
            model = "gemini-1.5-flash"
        )
        val evaluation2 = com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation(
            id = 802L,
            turn = turn2,
            totalScore = java.math.BigDecimal("6.0"),
            feedback = "조금 더 구체화가 필요합니다.",
            bestPractice = "Scope 별 차이를 설명해 주세요.",
            model = "heuristic"
        )

        given(interviewSessionRepository.findByIdAndUser_Id(session.id, user.id)).willReturn(session)
        given(interviewTurnEvaluationRepository.findAllByTurn_Session_Id(session.id)).willReturn(listOf(evaluation1, evaluation2))
        given(interviewTurnRepository.findAllDetailedBySessionIdOrderByTurnNoAsc(session.id)).willReturn(listOf(turn1, turn2))

        val result = service().getSessionResults(
            principal = AuthPrincipal(user.id, user.email, user.name, user.role),
            sessionId = session.id
        )

        assertThat(result.turns).hasSize(2)
        assertThat(result.turns[0].evaluation?.score).isEqualByComparingTo("8.5")
        assertThat(result.turns[1].evaluation?.providerUsed).isEqualTo("HEURISTIC")
        then(interviewTurnEvaluationRepository).should().findAllByTurn_Session_Id(session.id)
        then(interviewTurnRepository).should().findAllDetailedBySessionIdOrderByTurnNoAsc(session.id)
        then(interviewTurnEvaluationRepository).should(never()).findByTurn_Id(anyLong())
    }

    private fun service() = InterviewPracticeService(
        interviewAiOrchestrator = interviewAiOrchestrator,
        aiRoutingContextHolder = aiRoutingContextHolder,
        interviewEvaluationService = interviewEvaluationService,
        jobSkillCatalogService = jobSkillCatalogService,
        categoryRepository = categoryRepository,
        categoryContextResolver = categoryContextResolver,
        questionRepository = questionRepository,
        questionSetRepository = questionSetRepository,
        questionSetItemRepository = questionSetItemRepository,
        interviewSessionRepository = interviewSessionRepository,
        interviewTurnRepository = interviewTurnRepository,
        interviewTurnEvaluationRepository = interviewTurnEvaluationRepository,
        documentQuestionRepository = documentQuestionRepository,
        userQuestionAttemptRepository = userQuestionAttemptRepository,
        savedQuestionRepository = savedQuestionRepository,
        userRepository = userRepository,
        userGeminiApiKeyService = userGeminiApiKeyService,
        adminInterviewSettingsService = adminInterviewSettingsService,
        objectMapper = objectMapper,
        entityManager = entityManager
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        any<T>()
        return null as T
    }

    private fun createUser(id: Long = 1L) = User(
        id = id,
        email = "user@vlainter.com",
        password = "encoded",
        name = "테스터",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        geminiApiKeyEncrypted = "encrypted"
    )

    private fun createCategory(
        id: Long = 13L,
        name: String = "Spring",
        depth: Int = 2,
        path: String = "/dev/backend/spring",
        parent: QaCategory? = null
    ) = QaCategory(
        id = id,
        parent = parent,
        code = name.lowercase(),
        name = name,
        depth = depth,
        path = path
    )

    private fun createQuestion(
        id: Long,
        category: QaCategory,
        text: String = "Spring DI를 설명해 주세요."
    ) = QaQuestion(
        id = id,
        fingerprint = "fp-$id",
        questionText = text,
        canonicalAnswer = "의존성을 외부에서 주입하는 패턴입니다.",
        category = category,
        jobName = "백엔드개발자",
        skillName = category.name,
        difficulty = QuestionDifficulty.MEDIUM,
        sourceTag = QuestionSourceTag.SYSTEM,
        tagsJson = "[]"
    )
}
