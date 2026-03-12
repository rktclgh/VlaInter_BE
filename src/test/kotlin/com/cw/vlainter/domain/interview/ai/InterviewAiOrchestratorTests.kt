@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.global.config.properties.AiProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class InterviewAiOrchestratorTests {

    @Mock
    private lateinit var llmProviderRouter: LlmProviderRouter

    private val objectMapper = jacksonObjectMapper()
    private lateinit var orchestrator: InterviewAiOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = InterviewAiOrchestrator(
            aiProperties = AiProperties(),
            llmProviderRouter = llmProviderRouter,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `문서 답변 평가 프롬프트는 참고 답안을 보조 자료로만 사용하도록 안내한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "score": 82,
                      "feedback": "질문 의도에 맞게 답했습니다.",
                      "bestPractice": "결과를 더 또렷하게 말해 보세요.",
                      "rubric": {
                        "coverage": 84,
                        "accuracy": 80,
                        "communication": 82
                      },
                      "evidence": ["질문 의도 적합", "STAR 일부 충족"]
                    }
                """.trimIndent()
            )
        }

        orchestrator.evaluateDocumentAnswer(
            questionText = "포트폴리오 프로젝트에서 성능 병목을 어떻게 발견하고 개선하셨나요?",
            referenceAnswer = "문제 상황과 담당 역할을 설명한 뒤, 원인 분석과 개선 결과를 STAR 구조로 답합니다.",
            evidence = listOf("포트폴리오에 API 성능 개선 경험과 응답 속도 개선 내용이 기재되어 있음"),
            userAnswer = "당시 서비스 응답 지연이 심해 직접 병목을 추적했고 캐시 전략을 조정해 성능을 개선했습니다."
        )

        assertThat(capturedPrompt).contains("[STAR형 참고 답안]")
        assertThat(capturedPrompt).contains("평가는 반드시 사용자 답변 자체를 중심으로 수행")
        assertThat(capturedPrompt).contains("표현, 문장 순서, 단어 선택이 다르다는 이유만으로 감점하지 말 것")
        assertThat(capturedPrompt).contains("빠진 STAR 요소")
    }

    @Test
    fun `문서 질문 생성 프롬프트는 STAR형 referenceAnswer를 요구한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "프로젝트 성능 개선 과정에서 어떤 병목을 발견했고 어떻게 해결하셨나요?",
                          "questionType": "PORTFOLIO_PROJECT",
                          "referenceAnswer": "프로젝트 성능 병목을 발견한 상황을 먼저 설명합니다. 당시 제가 개선 역할을 맡아 병목 원인을 분석했습니다. 로그와 프로파일링으로 직렬화 비용 문제를 확인했습니다. 이후 캐시 전략을 조정하고 API 구조를 정리해 해결했습니다. 그 결과 초기 로딩 속도와 응답 안정성이 개선되었습니다.",
                          "evidence": [
                            "포트폴리오에 성능 개선과 API 최적화 경험이 기재되어 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "PORTFOLIO",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf("대시보드 초기 로딩 속도를 개선하기 위해 API 구조와 캐시 전략을 조정한 경험이 있다.")
        )

        assertThat(generated).hasSize(1)
        assertThat(capturedPrompt).contains("STAR형 모범답안")
        assertThat(capturedPrompt).contains("상황/과제/행동/결과가 자연스럽게 드러나야 함")
    }

    @Test
    fun `영어 문서 답변 평가 프롬프트는 영어 응답과 grammar 기준을 명시한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "score": 79,
                      "feedback": "The answer is relevant.",
                      "bestPractice": "Make the result more specific.",
                      "rubric": {
                        "coverage": 80,
                        "accuracy": 76,
                        "communication": 81
                      },
                      "evidence": ["Relevant", "Needs clearer result"]
                    }
                """.trimIndent()
            )
        }

        orchestrator.evaluateDocumentAnswer(
            questionText = "How did you diagnose and improve the performance bottleneck in your project?",
            referenceAnswer = "I would explain the situation, my role, the actions I took, and the measurable result.",
            evidence = listOf("The portfolio mentions reducing dashboard latency and restructuring the API response."),
            userAnswer = "I traced the bottleneck with profiling and changed the cache strategy.",
            language = InterviewLanguage.EN
        )

        assertThat(capturedPrompt).contains("feedback, bestPractice, and evidence must be written in English.")
        assertThat(capturedPrompt).contains("grammar, sentence completeness, clarity, and natural professional English quality")
        assertThat(capturedPrompt).contains("document-based interview evaluator")
    }

    @Test
    fun `영어 기술 질문 생성 프롬프트는 질문과 모범답안을 영어로 요구한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "How would you explain the trade-off between consistency and availability in Redis caching?",
                          "canonicalAnswer": "I would first define the trade-off, then explain the practical impact on cache design and invalidation.",
                          "tags": ["redis", "cache"]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        orchestrator.generateTechQuestions(
            jobName = "Backend Engineer",
            skillName = "Redis",
            difficulty = null,
            questionCount = 1,
            language = InterviewLanguage.EN
        )

        assertThat(capturedPrompt).contains("Generate realistic technical interview questions and reference answers in English.")
        assertThat(capturedPrompt).contains("questionText와 canonicalAnswer는 모두 English로 작성할 것")
    }
}
