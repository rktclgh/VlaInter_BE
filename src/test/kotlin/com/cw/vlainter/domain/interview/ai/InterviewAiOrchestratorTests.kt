@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.global.config.properties.AiProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertDoesNotThrow
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
            if (capturedPrompt.isBlank()) {
                capturedPrompt = invocation.getArgument(0)
            }
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
        assertThat(capturedPrompt).contains("evidenceKind")
        assertThat(capturedPrompt).contains("ACTUAL_EXPERIENCE와 PROJECT_OR_RESULT 발췌에서는 referenceAnswer를 STAR형 예시 답변으로 작성")
    }

    @Test
    fun `문서 질문 생성은 목표 개수보다 넉넉한 후보 수를 요청한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            if (capturedPrompt.isBlank()) {
                capturedPrompt = invocation.getArgument(0)
            }
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "프로젝트 성능 개선 과정에서 어떤 병목을 발견했고 어떻게 해결하셨나요?",
                          "questionType": "PORTFOLIO_PROJECT",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
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

        orchestrator.generateDocumentQuestions(
            fileTypeLabel = "PORTFOLIO",
            difficulty = null,
            questionCount = 3,
            contextSnippets = listOf("대시보드 초기 로딩 속도를 개선하기 위해 API 구조와 캐시 전략을 조정한 경험이 있다.")
        )

        assertThat(capturedPrompt).contains("- 총 5개 질문 생성")
    }

    @Test
    fun `자기소개서 질문 생성 프롬프트는 포부성 문장을 경험처럼 단정하지 않도록 제한한다`() {
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
                          "questionText": "후보자 경험을 더 좋게 만들고 싶다고 했는데, 입사 후 어떤 기준으로 그 관점을 실천하고 싶나요?",
                          "questionType": "INTRODUCE_FUTURE_PLAN",
                          "evidenceKind": "MOTIVATION_OR_ASPIRATION",
                          "referenceAnswer": "후보자 경험을 중요하게 보는 이유와 그 기준을 입사 후 어떻게 적용할지 차례로 설명합니다.",
                          "evidence": [
                            "후보자 경험을 더 좋게 만들고 싶고 불필요한 업무를 줄이겠다는 포부를 언급함"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "INTRODUCE",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf(
                """
                    [문서 발췌 1]
                    kind=MOTIVATION_OR_ASPIRATION
                    text=인턴으로서 단순히 채용만 하는 것이 아니라 후보자에게 더 좋은 경험을 주고 싶고, 불필요한 업무를 과감히 줄이겠다는 마음가짐을 가지고 있습니다.
                """.trimIndent()
            )
        )

        assertThat(generated).hasSize(1)
        assertThat(capturedPrompt).contains("자기소개서의 미래지향적 문장, 포부, 마음가짐, 가치관을 이미 수행한 경험처럼 단정하여 질문하지 말 것")
        assertThat(capturedPrompt).contains("INTRODUCE_MOTIVATION, INTRODUCE_VALUE, INTRODUCE_FUTURE_PLAN, INTRODUCE_EXPERIENCE")
    }

    @Test
    fun `문서 질문은 설명해 주세요로 끝나는 한국어 면접 문장도 허용한다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "JWT 기반 인증을 선택한 이유와 실무 적용 시 장단점을 설명해 주세요.",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "프로젝트에서 인증 체계를 빠르게 구축해야 하는 상황이 있었습니다. 당시 저는 서버 API 설계와 인증 로직 구현을 담당했습니다. JWT 기반 인증을 선택한 이유는 세션 방식보다 확장성과 클라이언트 분리 측면에서 유리했기 때문입니다. 실무 적용에서는 토큰 탈취와 무효화 같은 장단점을 함께 고려해 Refresh Token과 Redis 기반 세션 추적을 도입했습니다. 그 결과 인증 상태 관리의 유연성과 보안 통제를 동시에 확보할 수 있었습니다.",
                          "evidence": [
                            "JWT, Refresh Token, Redis 기반 인증 구조를 프로젝트에 적용한 경험이 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf("JWT, Refresh Token, Redis 기반 인증 구조를 프로젝트에 적용한 경험이 있다.")
        )

        assertThat(generated).hasSize(1)
        assertThat(generated.first().questionText).endsWith("설명해 주세요.")
    }

    @Test
    fun `혼합형 이력서의 지원동기 질문도 resume 질문으로 허용한다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "삼성SDS에 지원한 이유와 본인의 강점을 이 직무와 연결해서 설명해 주세요.",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "삼성SDS에 지원한 이유는 실제 대규모 시스템 운영과 문제 해결 경험을 더 넓은 환경에서 확장하고 싶었기 때문입니다. 저는 인턴과 프로젝트 경험에서 백엔드 구현과 개선을 맡아 본 경험이 있습니다. 특히 요구사항을 기능 구현에만 맞추지 않고 운영 관점까지 함께 보는 강점이 있습니다. 이런 강점이 삼성SDS의 직무에서도 빠르게 적응하고 기여하는 데 도움이 된다고 생각합니다.",
                          "evidence": [
                            "지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf("지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있다.")
        )

        assertThat(generated).hasSize(1)
        assertThat(generated.first().questionText).contains("지원한 이유")
    }

    @Test
    fun `이력서에서 introduce 타입으로 생성돼도 resume 질문으로 재매핑해 허용한다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "삼성SDS에 지원한 이유와 본인의 강점을 이 직무와 연결해서 설명해 주세요.",
                          "questionType": "INTRODUCE_VALUE",
                          "evidenceKind": "MOTIVATION_OR_ASPIRATION",
                          "referenceAnswer": "삼성SDS에 지원한 이유는 대규모 시스템 환경에서 문제 해결 역량을 확장하고 싶었기 때문입니다. 저는 프로젝트와 인턴 경험에서 백엔드 구현과 개선을 맡아 본 경험이 있습니다. 특히 요구사항을 구현에서 끝내지 않고 운영 관점까지 함께 보는 강점이 있습니다. 이런 강점이 해당 직무에서도 빠르게 적응하고 기여하는 데 도움이 된다고 생각합니다.",
                          "evidence": [
                            "지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf("지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있다.")
        )

        assertThat(generated).hasSize(1)
        assertThat(generated.first().questionType).isEqualTo("RESUME_MOTIVATION")
    }

    @Test
    fun `이력서에서 questionType이 비어 있어도 evidenceKind 기준으로 resume 동기 유형으로 정규화한다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "지원한 이유와 입사 후 어떤 기준으로 기여하고 싶은지 설명해 주세요.",
                          "questionType": "",
                          "evidenceKind": "MOTIVATION_OR_ASPIRATION",
                          "referenceAnswer": "해당 직무에 지원한 이유는 안정적인 서비스 운영을 더 깊게 경험하고 싶었기 때문입니다. 프로젝트와 인턴에서 사용자 경험과 운영 안정성의 중요성을 체감했고, 입사 후에는 기능 구현뿐 아니라 운영 관점의 판단 기준까지 함께 가져가고 싶습니다. 특히 장애 예방과 사용성 개선을 함께 고려하는 방식으로 기여하고자 합니다.",
                          "evidence": [
                            "지원동기와 입사 후 기여 방향을 설명한 이력서형 문장이 포함되어 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 1,
            contextSnippets = listOf("지원동기와 입사 후 기여 방향을 설명한 이력서형 문장이 포함되어 있다.")
        )

        assertThat(generated).hasSize(1)
        assertThat(generated.first().questionType).isEqualTo("RESUME_MOTIVATION")
    }

    @Test
    fun `문서 질문 생성은 일부만 통과해도 partial success로 반환한다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "삼성SDS에 지원한 이유와 본인의 강점을 이 직무와 연결해서 설명해 주세요.",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "삼성SDS에 지원한 이유는 실제 대규모 시스템 환경에서 문제 해결 경험을 확장하고 싶었기 때문입니다. 저는 프로젝트와 인턴 경험에서 백엔드 구현과 개선을 맡아 본 경험이 있습니다. 특히 요구사항을 구현에서 끝내지 않고 운영 관점까지 함께 보는 강점이 있습니다. 이런 강점이 해당 직무에서도 빠르게 적응하고 기여하는 데 도움이 된다고 생각합니다.",
                          "evidence": [
                            "지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있음"
                          ]
                        },
                        {
                          "questionText": "BACKEND 관점에서 설명",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "짧은 답",
                          "evidence": [
                            "잡음 근거"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = assertDoesNotThrow {
            orchestrator.generateDocumentQuestions(
                fileTypeLabel = "RESUME",
                difficulty = null,
                questionCount = 2,
                contextSnippets = listOf("지원동기와 직무 강점을 함께 설명한 문장이 이력서형 PDF에 포함되어 있다.")
            )
        }

        assertThat(generated).hasSize(1)
        assertThat(generated.first().questionText).contains("지원한 이유")
    }

    @Test
    fun `문서 질문 생성은 같은 source라도 다른 질문 축이면 함께 채택할 수 있다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "JWT 기반 인증 구조를 설계할 때 본인이 맡은 핵심 역할은 무엇이었는지 설명해 주세요.",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "프로젝트에서 인증 체계를 빠르게 설계해야 하는 상황이 있었습니다. 저는 인증 구조 설계와 구현을 맡았습니다. 특히 Access Token과 Refresh Token 흐름을 정리하고 Redis 기반 세션 추적 로직을 연결하는 역할을 담당했습니다. 그 결과 인증 처리의 일관성을 갖춘 구조를 만들 수 있었습니다.",
                          "evidence": [
                            "JWT와 Refresh Token, Redis 기반 인증 구조를 프로젝트에 적용한 경험이 있음"
                          ]
                        },
                        {
                          "questionText": "Refresh Token과 Redis를 함께 쓴 뒤 운영 측면에서 어떤 효과를 얻었는지 설명해 주세요.",
                          "questionType": "RESUME_RESULT",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "JWT만으로는 세션 추적과 즉시 무효화가 어려운 상황이 있었습니다. 저는 Refresh Token과 Redis를 함께 도입한 뒤 운영 상태를 더 안정적으로 통제할 수 있었습니다. 그 결과 세션 만료와 로그아웃 처리를 명확하게 관리할 수 있었고 운영 편의성도 높아졌습니다.",
                          "evidence": [
                            "JWT와 Refresh Token, Redis 기반 인증 구조를 프로젝트에 적용한 경험이 있음"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 2,
            contextSnippets = listOf("JWT와 Refresh Token, Redis 기반 인증 구조를 프로젝트에 적용한 경험이 있다.")
        )

        assertThat(generated).hasSize(2)
    }

    @Test
    fun `문서 질문 생성은 같은 프로젝트에 대해 같은 질문 축을 재표현한 경우 중복 채택하지 않는다`() {
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willReturn(
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "questions": [
                        {
                          "questionText": "AWS 루키 챔피언십에서 Slack 알림 봇을 개발하며 OCR 분석과 번역 기능을 접목하셨는데, 이 프로젝트에서 본인이 맡은 핵심 역할과 기술적 기여는 무엇인가요?",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "대회 프로젝트에서 OCR 분석과 번역 기능을 결합한 Slack 알림 봇을 구현하는 상황이 있었습니다. 저는 핵심 기능 설계와 구현을 맡았습니다. 특히 OCR 결과 정제와 번역 파이프라인 연결을 담당했고, 서비스 흐름이 끊기지 않도록 알림 처리 구조를 정리했습니다. 그 결과 공지사항 요지 파악과 번역 기능이 통합된 봇을 완성할 수 있었습니다.",
                          "evidence": [
                            "AWS 루키 챔피언십 slack 부문 상 OCR분석 공지사항 요지 파악 번역 기능을 접목한 서비스 제작"
                          ]
                        },
                        {
                          "questionText": "AWS 루키 챔피언십의 Slack 알림 봇 프로젝트에서 OCR 분석과 번역 기능을 맡으셨다고 했는데, 본인이 담당한 핵심 역할은 무엇이었나요?",
                          "questionType": "RESUME_EXPERIENCE",
                          "evidenceKind": "ACTUAL_EXPERIENCE",
                          "referenceAnswer": "이 프로젝트에서는 OCR 분석과 번역 기능을 서비스 흐름에 안정적으로 붙이는 역할이 중요했습니다. 저는 해당 핵심 기능의 구현과 연결 구조를 담당했습니다. 특히 OCR 결과를 정제하고 번역 결과를 Slack 알림에 자연스럽게 반영하는 데 집중했습니다. 그 결과 공지사항 요지를 빠르게 전달하는 봇을 완성할 수 있었습니다.",
                          "evidence": [
                            "AWS 루키 챔피언십 Slack 부문 수상 및 OCR 분석 번역 기능 접목 서비스 제작"
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val generated = orchestrator.generateDocumentQuestions(
            fileTypeLabel = "RESUME",
            difficulty = null,
            questionCount = 2,
            contextSnippets = listOf("AWS 루키 챔피언십에서 OCR 분석과 번역 기능을 결합한 Slack 알림 봇을 개발했다.")
        )

        assertThat(generated).hasSize(1)
    }

    @Test
    fun `영어 문서 답변 평가 프롬프트는 평가 출력은 한국어로 유지하고 영어 문법 기준을 명시한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "score": 79,
                      "feedback": "답변은 질문과 관련이 있습니다.",
                      "bestPractice": "결과를 더 구체적으로 말해 보세요.",
                      "rubric": {
                        "coverage": 80,
                        "accuracy": 76,
                        "communication": 81
                      },
                      "evidence": ["질문 관련성 있음", "결과 보강 필요"]
                    }
                """.trimIndent()
            )
        }

        orchestrator.evaluateDocumentAnswer(
            questionText = "How did you diagnose and improve the performance bottleneck in your project?",
            referenceAnswer = "I would explain the situation, my role, the actions I took, and the measurable result.",
            evidence = listOf("The portfolio mentions reducing dashboard latency and restructuring the API response."),
            userAnswer = "I traced the bottleneck with profiling and changed the cache strategy.",
            language = InterviewLanguage.EN,
            responseLanguage = InterviewLanguage.KO
        )

        assertThat(capturedPrompt).contains("아래 입력을 바탕으로 한국어 JSON만 출력하세요.")
        assertThat(capturedPrompt).contains("grammar, sentence completeness, clarity, and natural professional English quality")
        assertThat(capturedPrompt).contains("당신은 면접 답변을 평가하는 면접관입니다.")
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

    @Test
    fun `배치 평가 프롬프트는 모든 항목을 한 번에 평가하고 key를 유지한다`() {
        var capturedPrompt = ""
        given(llmProviderRouter.generateJson(anyString(), nullable(Double::class.java))).willAnswer { invocation ->
            capturedPrompt = invocation.getArgument(0)
            LlmGenerationResult(
                model = "gemini",
                modelVersion = "v1",
                text = """
                    {
                      "items": [
                        {
                          "key": "101",
                          "score": 78,
                          "feedback": "질문 의도에는 대체로 맞습니다.",
                          "bestPractice": "결과를 조금 더 구체화해 보세요.",
                          "rubric": {
                            "coverage": 80,
                            "accuracy": 74,
                            "communication": 79
                          },
                          "evidence": ["질문 의도 적합", "결과 근거 보강 필요"]
                        },
                        {
                          "key": "102",
                          "score": 65,
                          "feedback": "동기 설명은 있으나 근거가 조금 약합니다.",
                          "bestPractice": "판단 기준과 실제 적용 계획을 함께 설명해 보세요.",
                          "rubric": {
                            "coverage": 68,
                            "accuracy": 60,
                            "communication": 67
                          },
                          "evidence": ["동기 설명 존재", "근거 부족"]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val result = orchestrator.evaluateTurnsBatch(
            listOf(
                BatchTurnEvaluationInput(
                    key = "101",
                    kind = "TECH",
                    answerLanguage = "EN",
                    questionText = "How would you explain JWT refresh token rotation?",
                    referenceAnswer = "I would explain why rotation reduces replay risk and how session revocation works.",
                    userAnswer = "I would rotate refresh tokens to reduce replay risk and revoke sessions on mismatch."
                ),
                BatchTurnEvaluationInput(
                    key = "102",
                    kind = "DOCUMENT",
                    answerLanguage = "KO",
                    questionText = "후보자 경험을 중요하게 보는 이유와 입사 후 적용 계획을 설명해 주세요.",
                    questionType = "INTRODUCE_FUTURE_PLAN",
                    referenceAnswer = "지원 동기와 실제 적용 계획을 차례로 설명합니다.",
                    evidence = listOf("후보자 경험을 더 좋게 만들고 싶다는 포부를 기재함"),
                    userAnswer = "지원자가 채용 과정에서 가장 먼저 체감하는 것이 안내 품질이라고 생각해 이 관점을 중요하게 봅니다."
                )
            ),
            responseLanguage = InterviewLanguage.KO
        )

        assertThat(result).hasSize(2)
        assertThat(result).containsKeys("101", "102")
        assertThat(capturedPrompt).contains("반드시 모든 입력 key를 유지해서 반환")
        assertThat(capturedPrompt).contains("\"kind\":\"TECH\"")
        assertThat(capturedPrompt).contains("\"kind\":\"DOCUMENT\"")
        assertThat(capturedPrompt).contains("answerLanguage=EN 이면 communication 점수에 grammar")
    }
}
