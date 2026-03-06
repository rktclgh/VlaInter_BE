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
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.4)
            parseGeneratedDocumentQuestions(generated.text, fileTypeLabel).take(questionCount)
        }.onFailure { ex ->
            logger.warn("문서 질문 생성 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) {
                buildHeuristicDocumentQuestions(fileTypeLabel, difficulty, questionCount, contextSnippets)
            } else {
                throw ex
            }
        }
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

    private fun parseGeneratedDocumentQuestions(raw: String, fileTypeLabel: String): List<GeneratedDocumentQuestion> {
        val node = objectMapper.readTree(raw)
        return node["questions"]
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
    }

    private fun buildHeuristicDocumentQuestions(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): List<GeneratedDocumentQuestion> {
        val evidencePool = contextSnippets
            .flatMap { snippet ->
                snippet.split("\n", ".", "?", "!")
                    .map { it.replace(Regex("\\s+"), " ").trim() }
            }
            .filter { it.length in 18..220 }
            .distinct()
            .take(questionCount.coerceAtLeast(3) * 2)

        val questionType = when (fileTypeLabel.uppercase()) {
            "RESUME" -> "RESUME_EXPERIENCE"
            "PORTFOLIO" -> "PORTFOLIO_PROJECT"
            "INTRODUCE" -> "INTRODUCE_MOTIVATION"
            else -> "${fileTypeLabel.uppercase()}_DOCUMENT"
        }

        val templates = when (fileTypeLabel.uppercase()) {
            "RESUME" -> listOf(
                "이 경험에서 맡은 역할과 실제로 기여한 부분을 구체적으로 설명해 주세요.",
                "이 활동을 통해 가장 크게 성장한 역량은 무엇이었나요?",
                "이력에 적은 성과를 만들기 위해 어떤 의사결정을 했는지 말씀해 주세요."
            )
            "PORTFOLIO" -> listOf(
                "이 프로젝트에서 본인이 담당한 역할과 핵심 기술 선택 이유를 설명해 주세요.",
                "구현 과정에서 가장 어려웠던 문제와 해결 방식을 구체적으로 말씀해 주세요.",
                "이 결과물을 다시 만든다면 어떤 부분을 개선할지 설명해 주세요."
            )
            else -> listOf(
                "자기소개서에서 강조한 강점을 실제 경험과 연결해서 설명해 주세요.",
                "이 내용을 바탕으로 지원 동기와 직무 적합성을 구체적으로 말씀해 주세요.",
                "이 경험이 현재 지원 직무와 어떻게 연결되는지 설명해 주세요."
            )
        }

        val difficultyGuide = when (difficulty) {
            QuestionDifficulty.HARD -> "구체적인 수치, 의사결정 근거, 대안 비교까지 포함해 답할 수 있어야 합니다."
            QuestionDifficulty.EASY -> "핵심 경험과 역할 중심으로 답할 수 있어야 합니다."
            else -> "핵심 경험과 선택 이유, 결과를 균형 있게 설명할 수 있어야 합니다."
        }
        val safeEvidencePool = evidencePool.ifEmpty { listOf("") }

        return (0 until questionCount).map { index ->
            val evidence = safeEvidencePool[index % safeEvidencePool.size]
            val template = templates[index % templates.size]
            GeneratedDocumentQuestion(
                questionNo = index + 1,
                questionText = if (evidence.isNotBlank()) {
                    "$template\n문서에는 \"$evidence\" 라고 적혀 있는데, 이 부분을 중심으로 말씀해 주세요."
                } else {
                    template
                },
                questionType = questionType,
                referenceAnswer = difficultyGuide,
                evidence = listOfNotNull(evidence.takeIf { it.isNotBlank() })
            )
        }
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
