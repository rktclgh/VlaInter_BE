@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.entity.DocumentQuestion
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.InterviewMode
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.RevealPolicy
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import com.cw.vlainter.domain.interview.repository.InterviewTurnEvaluationRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.UserQuestionAttemptRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.OffsetDateTime

@ExtendWith(MockitoExtension::class)
class InterviewEvaluationServiceTests {

    @Mock
    private lateinit var interviewAiOrchestrator: InterviewAiOrchestrator

    @Mock
    private lateinit var interviewTurnRepository: InterviewTurnRepository

    @Mock
    private lateinit var interviewTurnEvaluationRepository: InterviewTurnEvaluationRepository

    @Mock
    private lateinit var userQuestionAttemptRepository: UserQuestionAttemptRepository

    @Mock
    private lateinit var userGeminiApiKeyService: UserGeminiApiKeyService

    @Mock
    private lateinit var selfProvider: ObjectProvider<InterviewEvaluationService>

    private val objectMapper = jacksonObjectMapper()
    private lateinit var service: InterviewEvaluationService

    @BeforeEach
    fun setUp() {
        service = InterviewEvaluationService(
            interviewAiOrchestrator = interviewAiOrchestrator,
            interviewTurnRepository = interviewTurnRepository,
            interviewTurnEvaluationRepository = interviewTurnEvaluationRepository,
            userQuestionAttemptRepository = userQuestionAttemptRepository,
            userGeminiApiKeyService = userGeminiApiKeyService,
            objectMapper = objectMapper,
            selfProvider = selfProvider
        )
    }

    @Test
    fun `문서 면접 fallback 평가는 참고 답안 유사도보다 질문 의도와 STAR 흐름을 우선한다`() {
        val turn = createDocumentTurn(
            answer = "당시 대시보드 초기 로딩이 느려 사용자 이탈이 생기고 있었습니다. 저는 성능 개선을 맡아 응답 시간을 직접 측정하고 병목 구간을 분석했습니다. 이후 직렬화 비용이 큰 응답 구조를 줄이고 캐시 전략을 다시 설계했습니다. 그 결과 체감 로딩 시간이 짧아졌고 관련 문의도 줄었습니다.",
            referenceAnswer = "APM과 인덱스 재설계를 통해 p95 지연 시간을 45퍼센트 줄였다고 STAR 구조로 설명합니다."
        )

        given(
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = turn.documentQuestion!!.questionText,
                referenceAnswer = turn.documentQuestion!!.referenceAnswer,
                evidence = objectMapper.readValue(turn.documentQuestion!!.evidenceJson, Array<String>::class.java).toList(),
                userAnswer = turn.userAnswer!!
            )
        ).willReturn(null)

        val result = invokeBuildEvaluation(turn, turn.userAnswer!!)

        assertThat(result.score.toInt()).isGreaterThanOrEqualTo(60)
        assertThat(result.feedback).contains("질문 의도")
        assertThat(result.bestPractice).isNotBlank()
        assertThat(result.model).isEqualTo("heuristic")
    }

    @Test
    fun `문서 면접 fallback 평가는 질문에서 벗어난 답변에 낮은 점수를 준다`() {
        val turn = createDocumentTurn(
            answer = "팀원들과 협업을 열심히 했고 맡은 일도 성실히 수행했습니다.",
            referenceAnswer = "문제 상황과 개선 행동, 결과를 STAR 구조로 설명합니다."
        )

        given(
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = turn.documentQuestion!!.questionText,
                referenceAnswer = turn.documentQuestion!!.referenceAnswer,
                evidence = objectMapper.readValue(turn.documentQuestion!!.evidenceJson, Array<String>::class.java).toList(),
                userAnswer = turn.userAnswer!!
            )
        ).willReturn(null)

        val result = invokeBuildEvaluation(turn, turn.userAnswer!!)

        assertThat(result.score.toInt()).isLessThan(55)
        assertThat(result.feedback).contains("질문이 묻는 핵심 경험")
        assertThat(result.bestPractice).contains("STAR")
        assertThat(result.model).isEqualTo("heuristic")
    }

    @Test
    fun `영어 문서 면접 fallback 평가는 영어 피드백과 문장 완성도 기준을 사용한다`() {
        val turn = createDocumentTurn(
            answer = "In that project, I owned the performance investigation. I profiled the API response path, found excessive serialization overhead, and changed the cache policy. As a result, the dashboard loaded faster and support complaints decreased.",
            referenceAnswer = "I would describe the situation, my responsibility, the actions I took, and the measurable outcome.",
            language = InterviewLanguage.EN
        )

        given(
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = turn.documentQuestion!!.questionText,
                referenceAnswer = turn.documentQuestion!!.referenceAnswer,
                evidence = objectMapper.readValue(turn.documentQuestion!!.evidenceJson, Array<String>::class.java).toList(),
                userAnswer = turn.userAnswer!!,
                language = InterviewLanguage.EN
            )
        ).willReturn(null)

        val result = invokeBuildEvaluation(turn, turn.userAnswer!!)

        assertThat(result.score.toInt()).isGreaterThanOrEqualTo(60)
        assertThat(result.feedback).doesNotContainPattern("[가-힣]")
        assertThat(result.bestPractice).doesNotContainPattern("[가-힣]")
        assertThat(result.bestPractice.lowercase()).containsAnyOf("star", "result", "document")
        assertThat(result.model).isEqualTo("heuristic")
    }

    private fun createDocumentTurn(
        answer: String,
        referenceAnswer: String?,
        language: InterviewLanguage = InterviewLanguage.KO
    ): InterviewTurn {
        val user = User(
            id = 1L,
            email = "tester@vlainter.com",
            password = "encoded",
            name = "테스터",
            status = UserStatus.ACTIVE,
            role = UserRole.USER
        )
        val session = InterviewSession(
            id = 10L,
            user = user,
            mode = InterviewMode.DOC,
            status = InterviewStatus.IN_PROGRESS,
            revealPolicy = RevealPolicy.PER_TURN,
            configJson = objectMapper.writeValueAsString(
                mapOf(
                    "meta" to mapOf(
                        "language" to language.name
                    )
                )
            ),
            startedAt = OffsetDateTime.now(),
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )
        val documentQuestion = DocumentQuestion(
            id = 100L,
            setId = 99L,
            userId = 1L,
            documentFileId = 77L,
            questionNo = 1,
            questionText = if (language == InterviewLanguage.EN) {
                "How did you identify and improve the performance bottleneck in your portfolio project?"
            } else {
                "포트폴리오 프로젝트에서 성능 병목을 어떻게 발견하고 개선하셨나요?"
            },
            questionType = "PORTFOLIO_PROJECT",
            referenceAnswer = referenceAnswer,
            evidenceJson = objectMapper.writeValueAsString(
                if (language == InterviewLanguage.EN) {
                    listOf(
                        "The portfolio describes improving dashboard startup latency and restructuring the API response.",
                        "It also mentions faster perceived speed and fewer support complaints."
                    )
                } else {
                    listOf(
                        "포트폴리오에 대시보드 초기 로딩 개선과 API 구조 조정 경험이 기재되어 있음",
                        "사용자 체감 속도 개선과 관련 문의 감소를 언급함"
                    )
                }
            )
        )
        return InterviewTurn(
            id = 200L,
            session = session,
            turnNo = 1,
            sourceTag = TurnSourceTag.DOC_RAG,
            documentQuestion = documentQuestion,
            questionTextSnapshot = documentQuestion.questionText,
            categorySnapshot = if (language == InterviewLanguage.EN) "Document-based mock interview" else "문서 기반 모의면접",
            userAnswer = answer,
            answeredAt = OffsetDateTime.now()
        )
    }

    private fun invokeBuildEvaluation(turn: InterviewTurn, answer: String): ReflectedEvaluationResult {
        val method = InterviewEvaluationService::class.java.getDeclaredMethod(
            "buildEvaluation",
            InterviewTurn::class.java,
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(service, turn, answer)
        val resultClass = result.javaClass
        return ReflectedEvaluationResult(
            score = resultClass.getMethod("getScore").invoke(result) as BigDecimal,
            feedback = resultClass.getMethod("getFeedback").invoke(result) as String,
            bestPractice = resultClass.getMethod("getBestPractice").invoke(result) as String,
            model = resultClass.getMethod("getModel").invoke(result) as String?
        )
    }

    private data class ReflectedEvaluationResult(
        val score: BigDecimal,
        val feedback: String,
        val bestPractice: String,
        val model: String?
    )
}
