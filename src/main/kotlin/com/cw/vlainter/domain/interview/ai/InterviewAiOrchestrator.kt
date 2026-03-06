package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.QaQuestion
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
