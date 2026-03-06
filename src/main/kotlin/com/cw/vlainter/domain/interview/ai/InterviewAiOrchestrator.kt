package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.global.config.properties.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class InterviewAiOrchestrator(
    private val aiProperties: AiProperties,
    private val llmProviderRouter: LlmProviderRouter,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun evaluateTechAnswer(question: QaQuestion?, userAnswer: String): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = buildEvaluationPrompt(question, userAnswer)
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            val parsed = parseEvaluationJson(generated.text)
            parsed.copy(model = generated.model, modelVersion = generated.modelVersion)
        }.onFailure { ex ->
            logger.warn("AI 평가 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) null else throw ex
        }
    }

    fun generateDocumentQuestions(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): List<GeneratedDocumentQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val prompt = buildDocumentQuestionPrompt(fileTypeLabel, difficulty, questionCount, contextSnippets)
        val generated = llmProviderRouter.generateJson(prompt, temperature = 0.4)
        val node = objectMapper.readTree(generated.text)
        val questions = node["questions"]
            ?.takeIf { it.isArray }
            ?.mapIndexedNotNull { index, item ->
                val questionText = item.text("questionText").ifBlank { return@mapIndexedNotNull null }
                GeneratedDocumentQuestion(
                    questionNo = index + 1,
                    questionText = questionText,
                    questionType = item.text("questionType").ifBlank { fileTypeLabel.uppercase() },
                    referenceAnswer = item.text("referenceAnswer").ifBlank { null },
                    evidence = item["evidence"]
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { evidenceItem -> evidenceItem.asText().trim().takeIf(String::isNotBlank) }
                        ?: emptyList()
                )
            }
            .orEmpty()

        return questions.take(questionCount)
    }

    fun evaluateDocumentAnswer(
        questionText: String,
        referenceAnswer: String?,
        evidence: List<String>,
        userAnswer: String
    ): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = """
            당신은 문서 기반 모의면접 평가관입니다.
            아래 입력을 바탕으로 한국어 JSON만 출력하세요.

            [질문]
            $questionText

            [참고 답안]
            ${referenceAnswer?.takeIf { it.isNotBlank() } ?: "(참고 답안 없음)"}

            [근거 포인트]
            ${if (evidence.isEmpty()) "(근거 없음)" else evidence.joinToString("\n- ", prefix = "- ")}

            [사용자 답변]
            $userAnswer

            출력 JSON 스키마:
            {
              "score": 0~100 숫자(소수점 2자리까지),
              "feedback": "총평(2~4문장)",
              "bestPractice": "개선 가이드(2~4문장)",
              "rubric": {
                "coverage": 0~100,
                "accuracy": 0~100,
                "communication": 0~100
              },
              "evidence": ["평가 근거", "..."]
            }

            규칙:
            - 반드시 JSON 객체만 반환
            - 답변이 문서 맥락과 어긋나면 낮은 점수를 부여
            - 사실 기반 근거와 전달력을 함께 평가
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            val parsed = parseEvaluationJson(generated.text)
            parsed.copy(model = generated.model, modelVersion = generated.modelVersion)
        }.onFailure { ex ->
            logger.warn("문서 기반 AI 평가 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) null else throw ex
        }
    }

    private fun buildEvaluationPrompt(question: QaQuestion?, userAnswer: String): String {
        val questionText = question?.questionText.orEmpty()
        val canonicalAnswer = question?.canonicalAnswer?.takeIf { it.isNotBlank() } ?: "(모범답안 없음)"
        val category = question?.category?.path ?: "(카테고리 없음)"
        val difficulty = question?.difficulty?.name ?: "(난이도 없음)"
        val tags = question?.tagsJson ?: "[]"

        return """
            당신은 기술면접 평가관입니다.
            아래 입력을 기반으로 한국어로 엄격한 JSON만 출력하세요.

            [질문]
            $questionText

            [모범답안]
            $canonicalAnswer

            [메타]
            category=$category
            difficulty=$difficulty
            tags=$tags

            [사용자 답변]
            $userAnswer

            출력 JSON 스키마:
            {
              "score": 0~100 숫자(소수점 2자리까지),
              "feedback": "총평(2~4문장)",
              "bestPractice": "개선 가이드(2~4문장)",
              "rubric": {
                "coverage": 0~100,
                "accuracy": 0~100,
                "communication": 0~100
              },
              "evidence": ["답변에서 잘한 점/부족한 점 근거", "..."]
            }

            규칙:
            - 반드시 JSON 객체만 반환 (코드블록 금지)
            - 점수는 관대하지 않게, 근거 중심으로 산정
            - 사용자 답변이 질문과 무관하면 낮은 점수 부여
        """.trimIndent()
    }

    private fun buildDocumentQuestionPrompt(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): String {
        val joinedContext = contextSnippets
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { snippet -> "[문서 발췌]\n$snippet" }

        return """
            당신은 채용 면접관입니다.
            아래 문서 발췌를 기반으로 지원자에게 물을 개인화 면접 질문을 생성하세요.
            질문은 반드시 면접관의 말투로 작성하세요.

            [문서 유형]
            $fileTypeLabel

            [난이도]
            ${difficulty?.name ?: "MIXED"}

            [문서 발췌]
            $joinedContext

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "면접 질문",
                  "questionType": "RESUME_EXPERIENCE | PORTFOLIO_PROJECT | INTRODUCE_MOTIVATION 등",
                  "referenceAnswer": "좋은 답변의 방향",
                  "evidence": ["질문의 근거가 된 문서 포인트", "..."]
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 질문 생성
            - 질문은 구체적이어야 하며 문서의 내용과 직접 연결되어야 함
            - 단순 나열형 질문 대신 이유, 역할, 의사결정, 결과를 묻는 면접형 질문 우선
            - 반드시 JSON만 출력
        """.trimIndent()
    }

    private fun parseEvaluationJson(raw: String): AiTurnEvaluation {
        val node = objectMapper.readTree(raw)
        val score = node.decimal("score").coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
        val feedback = node.text("feedback").ifBlank { "AI 피드백 생성에 실패했습니다." }
        val bestPractice = node.text("bestPractice").ifBlank { "질문 의도에 맞춰 핵심-근거-결론 구조로 답변하세요." }

        val rubricNode = node["rubric"]
        val rubric = linkedMapOf(
            "coverage" to rubricNode.decimal("coverage").coerceIn(BigDecimal.ZERO, BigDecimal("100.00")),
            "accuracy" to rubricNode.decimal("accuracy").coerceIn(BigDecimal.ZERO, BigDecimal("100.00")),
            "communication" to rubricNode.decimal("communication").coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
        )

        val evidence = node["evidence"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
            ?.take(6)
            ?: emptyList()

        return AiTurnEvaluation(
            score = score.setScale(2, RoundingMode.HALF_UP),
            feedback = feedback,
            bestPractice = bestPractice,
            rubricScoresJson = objectMapper.writeValueAsString(rubric),
            evidenceJson = objectMapper.writeValueAsString(evidence)
        )
    }

    private fun JsonNode?.decimal(field: String): BigDecimal {
        if (this == null || this.isMissingNode) return BigDecimal.ZERO
        val value = this[field] ?: return BigDecimal.ZERO
        return runCatching {
            when {
                value.isNumber -> value.decimalValue()
                else -> BigDecimal(value.asText().trim())
            }
        }.getOrDefault(BigDecimal.ZERO)
    }

    private fun JsonNode?.text(field: String): String {
        if (this == null || this.isMissingNode) return ""
        return this[field]?.asText()?.trim().orEmpty()
    }
}

data class AiTurnEvaluation(
    val score: BigDecimal,
    val feedback: String,
    val bestPractice: String,
    val rubricScoresJson: String,
    val evidenceJson: String,
    val model: String = "unknown",
    val modelVersion: String? = null
)

data class GeneratedDocumentQuestion(
    val questionNo: Int,
    val questionText: String,
    val questionType: String,
    val referenceAnswer: String?,
    val evidence: List<String>
)
