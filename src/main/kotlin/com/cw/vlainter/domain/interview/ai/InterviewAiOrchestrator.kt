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
        val temperatures = listOf(0.40, 0.50, 0.60, 0.70, 0.80)
        val collected = linkedMapOf<String, GeneratedDocumentQuestion>()
        val maxRounds = temperatures.size
        var round = 0
        var lastError: Exception? = null

        while (collected.size < questionCount && round < maxRounds) {
            val remaining = questionCount - collected.size
            val temperature = temperatures[minOf(round, temperatures.lastIndex)]
            val prompt = buildDocumentQuestionPrompt(fileTypeLabel, difficulty, remaining, contextSnippets)
            try {
                val generated = llmProviderRouter.generateJson(prompt, temperature = temperature)
                val parsed = parseGeneratedDocumentQuestions(generated.text, fileTypeLabel)
                val validated = validateGeneratedDocumentQuestions(parsed, fileTypeLabel)
                validated.forEach { item ->
                    val key = item.questionText.trim().lowercase()
                    if (key.isNotBlank() && !collected.containsKey(key)) {
                        collected[key] = item.copy(questionNo = collected.size + 1)
                    }
                }
            } catch (ex: Exception) {
                lastError = ex
                logger.warn(
                    "문서 질문 생성 재시도 실패(provider={}, round={}, remaining={}, temp={}): {}",
                    aiProperties.provider,
                    round + 1,
                    remaining,
                    temperature,
                    ex.message
                )
                if (isRateLimitError(ex)) {
                    logger.warn("문서 질문 생성 재시도 중단: rate limit/quota 감지")
                    break
                }
            }
            round += 1
        }

        if (collected.size >= questionCount) {
            return collected.values.take(questionCount).toList()
        }

        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "생성된 문서 질문/모범답안이 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
    }

    fun generateTechQuestions(
        jobName: String,
        skillName: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<GeneratedTechQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val labels = CategoryLabels(
            jobLabel = jobName.trim().ifBlank { "직무" },
            skillLabel = skillName.trim().ifBlank { "기술" }
        )
        val temperatures = listOf(0.45, 0.55, 0.65, 0.75, 0.85)
        val collected = linkedMapOf<String, GeneratedTechQuestion>()
        val maxRounds = temperatures.size
        var round = 0
        var lastError: Exception? = null

        while (collected.size < questionCount && round < maxRounds) {
            val remaining = questionCount - collected.size
            val temperature = temperatures[minOf(round, temperatures.lastIndex)]
            val prompt = buildTechQuestionPrompt(
                jobName = labels.jobLabel,
                skillName = labels.skillLabel,
                difficulty = difficulty,
                questionCount = remaining
            )
            try {
                val generated = llmProviderRouter.generateJson(prompt, temperature = temperature)
                val parsed = parseGeneratedTechQuestions(generated.text)
                val validated = validateGeneratedTechQuestions(parsed, labels)
                validated.forEach { item ->
                    val key = item.questionText.trim().lowercase()
                    if (key.isNotBlank() && !collected.containsKey(key)) {
                        collected[key] = item
                    }
                }
            } catch (ex: Exception) {
                lastError = ex
                logger.warn(
                    "기술 질문 생성 재시도 실패(provider={}, round={}, remaining={}, temp={}): {}",
                    aiProperties.provider,
                    round + 1,
                    remaining,
                    temperature,
                    ex.message
                )
                if (isRateLimitError(ex)) {
                    logger.warn("기술 질문 생성 재시도 중단: rate limit/quota 감지")
                    break
                }
            }
            round += 1
        }

        if (collected.size >= questionCount) {
            return collected.values.take(questionCount).toList()
        }

        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "생성된 기술 질문/모범답안이 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
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

    fun validateEvidenceSnippets(fileTypeLabel: String, snippets: List<String>): SnippetValidationResult {
        if (snippets.isEmpty()) {
            return SnippetValidationResult(emptyList(), emptyList())
        }

        val prompt = buildSnippetValidationPrompt(fileTypeLabel, snippets)
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.2)
            parseSnippetValidation(generated.text, snippets)
        }.onFailure { ex ->
            logger.warn("문서 발췌 유효성 검증 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse {
            // 검증 실패 시에는 보수적으로 휴리스틱만 통과한 항목만 사용한다.
            val accepted = snippets.filter(::isMeaningfulEvidence)
            val details = snippets.mapIndexed { index, snippet ->
                ValidatedSnippet(
                    index = index,
                    snippet = snippet,
                    accepted = accepted.contains(snippet),
                    reason = if (accepted.contains(snippet)) "heuristic_pass" else "heuristic_reject"
                )
            }
            SnippetValidationResult(acceptedSnippets = accepted, details = details)
        }
    }

    private fun buildEvaluationPrompt(question: QaQuestion?, userAnswer: String): String {
        val questionText = question?.questionText.orEmpty()
        val canonicalAnswer = question?.canonicalAnswer?.takeIf { it.isNotBlank() } ?: "(모범답안 없음)"
        val category = question?.category?.name ?: "(카테고리 없음)"
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
                  "referenceAnswer": "이 질문에 대한 이상적인 모범답안(면접 답변 형식, 4~8문장)",
                  "evidence": ["질문의 근거가 된 문서 포인트", "..."]
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 질문 생성
            - 질문은 구체적이어야 하며 문서의 내용과 직접 연결되어야 함
            - 단순 나열형 질문 대신 이유, 역할, 의사결정, 결과를 묻는 면접형 질문 우선
            - OCR 오류처럼 보이는 깨진 문자열, 무의미한 영문 대문자 나열, 문맥이 없는 잡음은 근거로 사용하지 말 것
            - 말이 안 되는 발췌는 건너뛰고, 의미가 분명한 다른 발췌를 선택할 것
            - 질문에 문서 발췌를 그대로 길게 인용하지 말고 자연스러운 면접 문장으로 바꿀 것
            - 반드시 JSON만 출력
        """.trimIndent()
    }

    private fun buildTechQuestionPrompt(
        jobName: String,
        skillName: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): String {
        val difficultyGuide = when (difficulty ?: QuestionDifficulty.MEDIUM) {
            QuestionDifficulty.EASY -> "기본 개념, 핵심 구성요소, 대표 사용 사례 중심으로 묻습니다."
            QuestionDifficulty.MEDIUM -> "실무 적용 상황, 설계 이유, 트레이드오프 판단을 묻습니다."
            QuestionDifficulty.HARD -> "복합적인 문제 해결, 대안 비교, 의사결정 근거를 깊게 묻습니다."
        }
        return """
            당신은 기술면접 질문 출제관입니다.
            아래 직무와 기술을 기준으로 실전형 기술면접 질문과 모범답안을 생성하세요.

            [직무]
            $jobName

            [기술]
            $skillName

            [난이도]
            ${difficulty?.name ?: "MEDIUM"}

            [난이도 기준]
            $difficultyGuide

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "기술면접 질문",
                  "canonicalAnswer": "이 질문에 대한 이상적인 면접 모범답안(4~8문장)",
                  "tags": ["tag1", "tag2"]
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 질문 생성
            - 질문은 반드시 ${skillName} 자체 또는 그 핵심 개념/구성요소를 중심으로 만들어야 함
            - 질문은 개념/문제해결/설계 관점을 섞되, 같은 유형의 질문을 반복하지 말 것
            - 단순 정의 암기형 질문만 내지 말고, 실무 상황과 의사결정이 드러나는 질문을 우선할 것
            - ${skillName}과 직접 관련 없는 운영/배포 일반론 질문은 금지
            - 질문 문장에 raw category code, path, BACKEND SPRING 같은 기계적인 표현을 넣지 말 것
            - 자연스러운 한국어 면접 문장으로 작성할 것
            - 너무 포괄적인 질문, 어느 기술에도 통할 법한 질문, 기술명이 빠진 질문은 금지
            - 모범답안은 실제 면접에서 답하는 문장으로 4~8문장 작성하고, 핵심 근거와 실무 포인트를 포함할 것
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
                    questionType = item.text("questionType").ifBlank { toDocumentQuestionType(fileTypeLabel) },
                    referenceAnswer = item.text("referenceAnswer").ifBlank { null },
                    evidence = item["evidence"]
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { evidenceItem -> evidenceItem.asText().trim().takeIf(String::isNotBlank) }
                        ?: emptyList()
                )
            }
            .orEmpty()
    }

    private fun validateGeneratedDocumentQuestions(
        generated: List<GeneratedDocumentQuestion>,
        fileTypeLabel: String
    ): List<GeneratedDocumentQuestion> {
        val seen = linkedSetOf<String>()
        return generated.mapNotNull { item ->
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            if (!isUsableDocumentQuestion(normalizedQuestion, fileTypeLabel)) return@mapNotNull null

            val fingerprint = normalizedQuestion
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            if (!seen.add(fingerprint)) return@mapNotNull null

            val normalizedAnswer = item.referenceAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isGuideLikeModelAnswer(it) && isDocumentAnswerLinkedToQuestion(it, normalizedQuestion) }
                ?: return@mapNotNull null

            val normalizedEvidence = item.evidence
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 8 }
                .distinct()
                .take(4)
            if (normalizedEvidence.isEmpty()) return@mapNotNull null

            item.copy(
                questionText = normalizedQuestion,
                referenceAnswer = normalizedAnswer,
                evidence = normalizedEvidence
            )
        }
    }

    private fun parseGeneratedTechQuestions(raw: String): List<GeneratedTechQuestion> {
        val node = objectMapper.readTree(raw)
        return node["questions"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val questionText = item.text("questionText").ifBlank { return@mapNotNull null }
                GeneratedTechQuestion(
                    questionText = questionText,
                    canonicalAnswer = item.text("canonicalAnswer").ifBlank { null },
                    tags = item["tags"]
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { tag -> tag.asText().trim().takeIf(String::isNotBlank) }
                        ?: emptyList()
                )
            }
            .orEmpty()
    }

    private fun validateGeneratedTechQuestions(
        generated: List<GeneratedTechQuestion>,
        labels: CategoryLabels
    ): List<GeneratedTechQuestion> {
        val seen = linkedSetOf<String>()
        return generated.mapNotNull { item ->
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            if (!isUsableTechQuestion(normalizedQuestion)) return@mapNotNull null
            val fingerprint = normalizedQuestion
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            if (!seen.add(fingerprint)) return@mapNotNull null

            val normalizedAnswer = item.canonicalAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isGuideLikeModelAnswer(it) }
                ?: return@mapNotNull null

            item.copy(
                questionText = normalizedQuestion,
                canonicalAnswer = normalizedAnswer,
                tags = normalizeTechTags(item.tags, labels)
            )
        }
    }

    private fun isUsableDocumentQuestion(questionText: String, fileTypeLabel: String): Boolean {
        if (questionText.length < 18) return false
        if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED)\\b").containsMatchIn(questionText)) return false
        val lowered = questionText.lowercase()
        val banned = listOf("어떤 기술에도", "일반적으로", "상식적으로", "포괄적으로")
        if (banned.any { lowered.contains(it) }) return false

        val contextKeywords = when (fileTypeLabel.trim().uppercase()) {
            "RESUME", "이력서" -> listOf("경험", "프로젝트", "역할", "성과", "업무")
            "PORTFOLIO", "포트폴리오" -> listOf("프로젝트", "구현", "아키텍처", "문제", "개선")
            "INTRODUCE", "자기소개서" -> listOf("동기", "경험", "배운", "가치", "강점")
            else -> listOf("경험", "프로젝트", "문서", "근거")
        }
        return contextKeywords.any { lowered.contains(it) }
    }

    private fun isDocumentAnswerLinkedToQuestion(answer: String, questionText: String): Boolean {
        val answerTokens = answer.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()
        if (answerTokens.size < 6) return false

        val questionTokens = questionText.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()

        return questionTokens.intersect(answerTokens).size >= 2
    }

    private fun isRateLimitError(ex: Exception): Boolean {
        val message = ex.message?.lowercase().orEmpty()
        return "http 429" in message ||
            "resource_exhausted" in message ||
            "quota exceeded" in message ||
            "rate limit" in message
    }

    private fun isUsableTechQuestion(questionText: String): Boolean {
        if (questionText.length < 18) return false
        // NOTE:
        // 아래 하드 필터는 난이도/도메인 다양성에서 과도하게 탈락을 발생시켜 비활성화한다.
        // - raw enum/path 포함 즉시 탈락
        // - 기술 키워드 미포함 즉시 탈락
        // 품질 보정은 프롬프트 규칙과 후속 평가 단계에서 처리한다.
        // val lowered = questionText.lowercase()
        // val bannedFragments = listOf("backend spring", "frontend react", "/tech/", "raw category", "category path")
        // if (bannedFragments.any { lowered.contains(it) }) return false
        // if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED)\\b").containsMatchIn(questionText)) return false
        // if (Regex("설명해 주세요\\.?$").find(questionText)?.range?.first == 0 && questionText.length < 24) return false
        // val skillKeywords = buildSkillKeywords(skillLabel)
        // return skillKeywords.any { keyword -> keyword.isNotBlank() && lowered.contains(keyword) }
        return true
    }

    private fun isGuideLikeModelAnswer(answer: String): Boolean {
        val trimmed = answer.trim()
        return trimmed.startsWith("질문 의도") ||
            trimmed.startsWith("좋은 답변은") ||
            trimmed.startsWith("핵심 개념") ||
            trimmed.contains("답변해") ||
            trimmed.contains("설명해야")
    }

    private fun normalizeTechTags(tags: List<String>, labels: CategoryLabels): List<String> {
        val normalized = tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { raw ->
                raw.uppercase()
                    .replace(Regex("[^A-Z0-9가-힣]+"), "_")
                    .trim('_')
            }
            .filterNot { it in setOf("TECH", "BACKEND", "FRONTEND", "SYSTEM_ARCH", "EMBEDDED") }
            .distinct()
            .toMutableList()
        if (normalized.isEmpty()) {
            normalized += labels.skillLabel.uppercase().replace(Regex("[^A-Z0-9가-힣]+"), "_").trim('_')
        }
        return normalized
    }

    private fun buildSnippetValidationPrompt(fileTypeLabel: String, snippets: List<String>): String {
        val payload = snippets.mapIndexed { index, snippet ->
            mapOf("index" to index, "snippet" to snippet)
        }
        return """
            당신은 OCR/문서 발췌 품질 검증기입니다.
            아래는 ${fileTypeLabel} 문서에서 추출한 발췌 목록입니다.
            각 발췌가 질문 생성 근거로 사용 가능한지 판정하세요.

            [입력]
            ${objectMapper.writeValueAsString(payload)}

            출력 JSON 스키마:
            {
              "results": [
                { "index": 0, "accepted": true, "reason": "판정 근거 요약" }
              ]
            }

            규칙:
            - 반드시 JSON만 출력
            - accepted=true 조건:
              1) 문장이 문법적으로 읽을 수 있고
              2) 의미가 끊기지 않으며
              3) 면접 질문 근거로 쓸 수 있는 구체성이 있음
            - OCR 잡음, 깨진 토큰 나열, 의미 없는 숫자/대문자열은 rejected
            - reason은 1문장으로 간결하게
        """.trimIndent()
    }

    private fun parseSnippetValidation(raw: String, snippets: List<String>): SnippetValidationResult {
        val node = objectMapper.readTree(raw)
        val results = node["results"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val index = item["index"]?.asInt() ?: return@mapNotNull null
                if (index !in snippets.indices) return@mapNotNull null
                ValidatedSnippet(
                    index = index,
                    snippet = snippets[index],
                    accepted = item["accepted"]?.asBoolean() == true,
                    reason = item["reason"]?.asText()?.trim().orEmpty()
                )
            }
            .orEmpty()
            .distinctBy { it.index }

        val merged = snippets.mapIndexed { index, snippet ->
            results.find { it.index == index } ?: ValidatedSnippet(
                index = index,
                snippet = snippet,
                accepted = false,
                reason = "no_result"
            )
        }
        val accepted = merged.filter { it.accepted }.map { it.snippet }
        return SnippetValidationResult(
            acceptedSnippets = accepted,
            details = merged
        )
    }

    private fun isMeaningfulEvidence(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val uppercaseRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length.toDouble()
        val tokenCount = text.split(" ").count { it.isNotBlank() }
        val suspiciousTokens = text.split(" ").count { token ->
            token.length >= 6 && token.count(Char::isUpperCase) >= 4
        }
        return tokenCount >= 4 && uppercaseRatio < 0.72 && suspiciousTokens <= 2
    }

    private fun toDocumentQuestionType(fileTypeLabel: String): String {
        return when (fileTypeLabel.trim().uppercase()) {
            "RESUME", "이력서" -> "RESUME_EXPERIENCE"
            "PORTFOLIO", "포트폴리오" -> "PORTFOLIO_PROJECT"
            "INTRODUCE", "자기소개서" -> "INTRODUCE_MOTIVATION"
            else -> "${fileTypeLabel.trim().uppercase()}_DOCUMENT"
        }
    }

    private fun parseEvaluationJson(raw: String): AiTurnEvaluation {
        val node = objectMapper.readTree(raw)
        val score = node.decimal("score").coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
        val feedback = node.text("feedback").ifBlank { "AI 피드백 생성에 실패했습니다." }
        val bestPractice = node.text("bestPractice").ifBlank { "" }

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

data class GeneratedTechQuestion(
    val questionText: String,
    val canonicalAnswer: String? = null,
    val tags: List<String> = emptyList()
)

data class SnippetValidationResult(
    val acceptedSnippets: List<String>,
    val details: List<ValidatedSnippet>
)

data class ValidatedSnippet(
    val index: Int,
    val snippet: String,
    val accepted: Boolean,
    val reason: String
)

private data class CategoryLabels(
    val jobLabel: String,
    val skillLabel: String
)
