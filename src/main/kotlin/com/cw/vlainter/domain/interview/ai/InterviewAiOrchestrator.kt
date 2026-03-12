package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
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

    fun evaluateTechAnswer(
        question: QaQuestion?,
        userAnswer: String,
        language: InterviewLanguage = InterviewLanguage.KO,
        responseLanguage: InterviewLanguage = language,
        questionTextOverride: String? = null,
        canonicalAnswerOverride: String? = null
    ): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = buildEvaluationPrompt(
            question = question,
            userAnswer = userAnswer,
            answerLanguage = language,
            responseLanguage = responseLanguage,
            questionTextOverride = questionTextOverride,
            canonicalAnswerOverride = canonicalAnswerOverride
        )
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
        contextSnippets: List<String>,
        language: InterviewLanguage = InterviewLanguage.KO
    ): List<GeneratedDocumentQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val temperatures = listOf(0.45, 0.60, 0.75)
        val collected = linkedMapOf<String, GeneratedDocumentQuestion>()
        val maxRounds = temperatures.size
        var round = 0
        var lastError: Exception? = null

        while (collected.size < questionCount && round < maxRounds) {
            val remaining = questionCount - collected.size
            val temperature = temperatures[minOf(round, temperatures.lastIndex)]
            val prompt = buildDocumentQuestionPrompt(fileTypeLabel, difficulty, remaining, contextSnippets, language)
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
                if (shouldStopRetry(ex)) {
                    logger.warn("문서 질문 생성 재시도 중단: transient overload/rate limit 감지")
                    break
                }
            }
            round += 1
        }

        if (collected.size >= questionCount) {
            return collected.values.take(questionCount).toList()
        }
        if (lastError is GeminiTransientException) {
            throw lastError
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
        questionCount: Int,
        language: InterviewLanguage = InterviewLanguage.KO
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
                questionCount = remaining,
                language = language
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
                if (shouldStopRetry(ex)) {
                    logger.warn("기술 질문 생성 재시도 중단: transient overload/rate limit 감지")
                    break
                }
            }
            round += 1
        }

        if (collected.size >= questionCount) {
            return collected.values.take(questionCount).toList()
        }
        if (lastError is GeminiTransientException) {
            throw lastError
        }

        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "생성된 기술 질문/모범답안이 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
    }

    fun generateTechQuestionsBatch(
        jobName: String,
        skillNames: List<String>,
        difficulty: QuestionDifficulty?,
        questionCountPerSkill: Int,
        language: InterviewLanguage = InterviewLanguage.KO
    ): List<GeneratedSkillTechQuestion> {
        require(questionCountPerSkill > 0) { "questionCountPerSkill must be positive." }
        val normalizedSkills = skillNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (normalizedSkills.isEmpty()) return emptyList()

        val temperatures = listOf(0.45, 0.55, 0.65, 0.75, 0.85)
        var lastError: Exception? = null

        for ((index, temperature) in temperatures.withIndex()) {
            val prompt = buildBatchTechQuestionPrompt(
                jobName = jobName.trim().ifBlank { "직무" },
                skillNames = normalizedSkills,
                difficulty = difficulty,
                questionCountPerSkill = questionCountPerSkill,
                language = language
            )
            try {
                val generated = llmProviderRouter.generateJson(prompt, temperature = temperature)
                val parsed = parseGeneratedSkillTechQuestions(generated.text)
                val validated = validateGeneratedSkillTechQuestions(
                    generated = parsed,
                    jobName = jobName,
                    skillNames = normalizedSkills
                )
                if (validated.isNotEmpty()) {
                    return validated
                }
            } catch (ex: Exception) {
                lastError = ex
                logger.warn(
                    "기술 질문 배치 생성 재시도 실패(provider={}, round={}, temp={}): {}",
                    aiProperties.provider,
                    index + 1,
                    temperature,
                    ex.message
                )
                if (shouldStopRetry(ex)) {
                    logger.warn("기술 질문 배치 생성 재시도 중단: transient overload/rate limit 감지")
                    break
                }
            }
        }

        if (lastError is GeminiTransientException) {
            throw lastError
        }
        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "생성된 기술 질문/모범답안이 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
    }

    fun evaluateDocumentAnswer(
        questionText: String,
        questionType: String? = null,
        referenceAnswer: String?,
        evidence: List<String>,
        userAnswer: String,
        language: InterviewLanguage = InterviewLanguage.KO,
        responseLanguage: InterviewLanguage = language
    ): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val starRecommended = documentQuestionTypeRequiresStar(questionType)
        val localizedQuestionType = questionType?.trim().orEmpty().ifBlank {
            emptyLocalizedPlaceholder(responseLanguage, "question type")
        }

        val prompt = """
            ${evaluationSystemRole(responseLanguage, "document-based interview evaluator")}
            ${jsonLanguageInstruction(responseLanguage)}

            [질문]
            $questionText

            [질문 유형]
            $localizedQuestionType

            [STAR형 참고 답안]
            ${referenceAnswer?.takeIf { it.isNotBlank() } ?: emptyLocalizedPlaceholder(responseLanguage, "reference answer")}

            [근거 포인트]
            ${if (evidence.isEmpty()) emptyLocalizedPlaceholder(responseLanguage, "evidence") else evidence.joinToString("\n- ", prefix = "- ")}

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
            - 평가는 반드시 사용자 답변 자체를 중심으로 수행
            - 참고 답안과 표현, 문장 순서, 단어 선택이 다르다는 이유만으로 감점하지 말 것
            - 참고 답안은 정답 매칭용이 아니라, 빠진 관점과 ${if (starRecommended) "STAR 보강 포인트" else "답변 보강 포인트"}를 찾는 보조 자료로만 활용할 것
            - coverage는 질문 의도 적합성을 가장 우선으로 평가하고, ${if (starRecommended) "STAR 구조 완성도를 함께 반영" else "동기/가치관/실행 계획의 구체성을 함께 반영"}한 점수로 산정
            - accuracy는 기술적 설명의 타당성, 논리, 근거, 성과 설명의 설득력을 중심으로 산정
            - communication은 답변 구조, 전달력, 면접 답변다운 정리 정도를 평가
            - 답변이 문서 맥락과 명확히 어긋나거나 주장 근거가 부족하면 낮은 점수를 부여
            - ${if (starRecommended) "경험/성과형 질문이므로 Situation, Task, Action, Result 중 빠진 축을 함께 점검" else "동기/가치관형 질문이므로 지원 맥락, 판단 기준, 실제 적용 계획, 근거 경험의 연결성을 함께 점검"}
            - bestPractice에는 ${if (starRecommended) "빠진 STAR 요소(Situation, Task, Action, Result)" else "빠진 동기/가치관/실행 계획 요소"}와 보강할 근거를 구체적으로 적을 것
            ${englishCommunicationRule(language)}
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

    fun evaluateIntroductionAnswer(
        userAnswer: String,
        language: InterviewLanguage = InterviewLanguage.KO,
        responseLanguage: InterviewLanguage = language
    ): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = """
            ${evaluationSystemRole(responseLanguage, "interviewer evaluating the first self-introduction answer")}
            ${jsonLanguageInstruction(responseLanguage)}

            [질문]
            ${localizedIntroQuestion(language)}

            [사용자 답변]
            $userAnswer

            출력 JSON 스키마:
            {
              "score": 0~100 숫자(소수점 2자리까지),
              "feedback": "총평(2~4문장)",
              "bestPractice": "더 좋은 자기소개를 위한 개선 가이드(2~4문장)",
              "rubric": {
                "coverage": 0~100,
                "accuracy": 0~100,
                "communication": 0~100
              },
              "evidence": ["평가 근거", "..."]
            }

            규칙:
            - 반드시 JSON 객체만 반환
            - 경력/역할/강점/지원 맥락이 드러나는지 본다
            - 너무 길거나 핵심이 흐리면 감점한다
            - 존댓말, 전달력, 구조적 답변 여부를 함께 평가한다
            ${englishCommunicationRule(language)}
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            val parsed = parseEvaluationJson(generated.text)
            parsed.copy(model = generated.model, modelVersion = generated.modelVersion)
        }.onFailure { ex ->
            logger.warn("자기소개 AI 평가 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) null else throw ex
        }
    }

    fun evaluateTurnsBatch(
        items: List<BatchTurnEvaluationInput>,
        responseLanguage: InterviewLanguage = InterviewLanguage.KO
    ): Map<String, AiTurnEvaluation> {
        if (items.isEmpty()) return emptyMap()

        val prompt = """
            ${evaluationSystemRole(responseLanguage, "interview evaluator")}
            ${jsonLanguageInstruction(responseLanguage)}

            아래 각 항목을 서로 독립적으로 평가하고 반드시 JSON만 반환하세요.

            출력 JSON 스키마:
            {
              "items": [
                {
                  "key": "stable key",
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
              ]
            }

            공통 규칙:
            - 항목별 평가는 서로 섞지 말고 독립적으로 수행
            - 반드시 모든 입력 key를 유지해서 반환
            - feedback, bestPractice, evidence는 모두 ${responseLanguage.displayLanguageName()}로 작성
            - kind=TECH: 질문 의도 적합성, 기술 정확성, 실무 근거를 중심으로 평가
            - kind=DOCUMENT: 사용자 답변 자체를 중심으로 평가하고 referenceAnswer는 정답 매칭이 아니라 보조 힌트로만 활용
            - questionType이 INTRODUCE_MOTIVATION, INTRODUCE_VALUE, INTRODUCE_FUTURE_PLAN 인 DOCUMENT 항목은 STAR를 과도하게 강제하지 말고 동기, 판단 기준, 실제 적용 계획, 근거 연결성을 평가
            - 그 외 DOCUMENT 항목은 질문 의도와 STAR 흐름(Situation, Task, Action, Result)을 함께 평가
            - kind=INTRO: 자기소개 답변으로서 역할, 강점, 지원 맥락, 전달력을 평가
            - answerLanguage=EN 이면 communication 점수에 grammar, sentence completeness, clarity, and natural professional English quality를 반영

            [items]
            ${objectMapper.writeValueAsString(items)}
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            parseBatchEvaluationJson(generated.text).mapValues { (_, value) ->
                value.copy(model = generated.model, modelVersion = generated.modelVersion)
            }
        }.onFailure { ex ->
            logger.warn("배치 면접 평가 실패(provider={}, count={}): {}", aiProperties.provider, items.size, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) emptyMap() else throw ex
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

    private fun buildEvaluationPrompt(
        question: QaQuestion?,
        userAnswer: String,
        answerLanguage: InterviewLanguage,
        responseLanguage: InterviewLanguage,
        questionTextOverride: String?,
        canonicalAnswerOverride: String?
    ): String {
        val questionText = questionTextOverride
            ?: localizeInterviewText(question?.questionText.orEmpty(), answerLanguage, "interview question")
        val canonicalAnswer = canonicalAnswerOverride
            ?: localizeInterviewText(
                question?.canonicalAnswer?.takeIf { it.isNotBlank() },
                answerLanguage,
                "reference answer"
            )
            ?: emptyLocalizedPlaceholder(responseLanguage, "reference answer")
        val category = question?.category?.name ?: "(카테고리 없음)"
        val difficulty = question?.difficulty?.name ?: "(난이도 없음)"
        val tags = question?.tagsJson ?: "[]"

        return """
            ${evaluationSystemRole(responseLanguage, "technical interview evaluator")}
            ${jsonLanguageInstruction(responseLanguage)}

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
            ${englishCommunicationRule(answerLanguage)}
        """.trimIndent()
    }

    private fun buildDocumentQuestionPrompt(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>,
        language: InterviewLanguage
    ): String {
        val normalizedFileType = normalizeDocumentFileType(fileTypeLabel)
        val joinedContext = contextSnippets
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val rules = buildList {
            add("- 총 ${questionCount}개 질문 생성")
            add("- 질문은 구체적이어야 하며 문서의 내용과 직접 연결되어야 함")
            add("- 각 문서 발췌에는 kind=ACTUAL_EXPERIENCE | PROJECT_OR_RESULT | MOTIVATION_OR_ASPIRATION | VALUE_OR_ATTITUDE 라벨이 붙어 있으므로 반드시 이를 해석해 사용할 것")
            add("- questionType은 문서 유형과 발췌 kind에 맞는 값만 사용")
            addAll(documentQuestionTypeRules(normalizedFileType))
            addAll(documentQuestionPatternRules(normalizedFileType))
            add("- 질문에 문서 발췌를 그대로 길게 인용하지 말고 자연스러운 면접 문장으로 바꿀 것")
            add("- OCR 오류처럼 보이는 깨진 문자열, 무의미한 영문 대문자 나열, 문맥이 없는 잡음은 근거로 사용하지 말 것")
            add("- 말이 안 되는 발췌는 건너뛰고, 의미가 분명한 다른 발췌를 선택할 것")
            add("- 모든 questionText, referenceAnswer, evidence는 ${language.displayLanguageName()}로 작성할 것")
            add("- 반드시 JSON만 출력")
        }

        return """
            ${generationSystemRole(language, "hiring interviewer")}
            Generate personalized interview questions from the document snippets below.
            Questions and reference answers must be written in ${language.displayLanguageName()}.

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
                  "questionType": "RESUME_EXPERIENCE | RESUME_RESULT | PORTFOLIO_PROJECT | PORTFOLIO_RESULT | INTRODUCE_MOTIVATION | INTRODUCE_VALUE | INTRODUCE_FUTURE_PLAN | INTRODUCE_EXPERIENCE",
                  "evidenceKind": "ACTUAL_EXPERIENCE | PROJECT_OR_RESULT | MOTIVATION_OR_ASPIRATION | VALUE_OR_ATTITUDE",
                  "referenceAnswer": "경험/성과형 질문이면 STAR형 예시 답변, 동기/가치관형 질문이면 동기와 실행 계획이 드러나는 예시 답변",
                  "evidence": ["질문의 근거가 된 문서 포인트", "..."]
                }
              ]
            }

            규칙:
            ${rules.joinToString("\n")}
        """.trimIndent()
    }

    private fun buildTechQuestionPrompt(
        jobName: String,
        skillName: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        language: InterviewLanguage
    ): String {
        val difficultyGuide = when (difficulty ?: QuestionDifficulty.MEDIUM) {
            QuestionDifficulty.EASY -> "기본 개념, 핵심 구성요소, 대표 사용 사례 중심으로 묻습니다."
            QuestionDifficulty.MEDIUM -> "실무 적용 상황, 설계 이유, 트레이드오프 판단을 묻습니다."
            QuestionDifficulty.HARD -> "복합적인 문제 해결, 대안 비교, 의사결정 근거를 깊게 묻습니다."
        }
        return """
            ${generationSystemRole(language, "technical interview question author")}
            Generate realistic technical interview questions and reference answers in ${language.displayLanguageName()}.

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
            - questionText와 canonicalAnswer는 모두 ${language.displayLanguageName()}로 작성할 것
            - 반드시 JSON만 출력
        """.trimIndent()
    }

    private fun buildBatchTechQuestionPrompt(
        jobName: String,
        skillNames: List<String>,
        difficulty: QuestionDifficulty?,
        questionCountPerSkill: Int,
        language: InterviewLanguage
    ): String {
        val difficultyGuide = when (difficulty ?: QuestionDifficulty.MEDIUM) {
            QuestionDifficulty.EASY -> "기본 개념, 핵심 구성요소, 대표 사용 사례 중심으로 묻습니다."
            QuestionDifficulty.MEDIUM -> "실무 적용 상황, 설계 이유, 트레이드오프 판단을 묻습니다."
            QuestionDifficulty.HARD -> "복합적인 문제 해결, 대안 비교, 의사결정 근거를 깊게 묻습니다."
        }
        val skillList = skillNames.joinToString("\n") { "- $it" }
        return """
            ${generationSystemRole(language, "technical interview question author")}
            Generate realistic technical interview questions and reference answers in ${language.displayLanguageName()}.

            [직무]
            $jobName

            [기술 목록]
            $skillList

            [난이도]
            ${difficulty?.name ?: "MEDIUM"}

            [난이도 기준]
            $difficultyGuide

            출력 JSON 스키마:
            {
              "skills": [
                {
                  "skillName": "입력에 포함된 기술명 그대로",
                  "questions": [
                    {
                      "questionText": "기술면접 질문",
                      "canonicalAnswer": "이 질문에 대한 이상적인 면접 모범답안(4~8문장)",
                      "tags": ["tag1", "tag2"]
                    }
                  ]
                }
              ]
            }

            규칙:
            - 각 기술마다 ${questionCountPerSkill}개씩 생성
            - skillName은 반드시 입력 기술명 중 하나를 그대로 사용
            - 질문은 반드시 해당 skillName 자체 또는 그 핵심 개념/구성요소를 중심으로 만들어야 함
            - 다른 기술과 섞인 질문, 범용 운영/배포 일반론 질문은 금지
            - 질문은 개념/문제해결/설계 관점을 섞되 같은 유형을 반복하지 말 것
            - 자연스러운 한국어 면접 문장으로 작성할 것
            - 너무 포괄적인 질문, 어느 기술에도 통할 법한 질문, 기술명이 빠진 질문은 금지
            - 모범답안은 실제 면접에서 답하는 문장으로 4~8문장 작성하고 핵심 근거와 실무 포인트를 포함할 것
            - questionText와 canonicalAnswer는 모두 ${language.displayLanguageName()}로 작성할 것
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
                    evidenceKind = item.text("evidenceKind").ifBlank { defaultEvidenceKind(fileTypeLabel) },
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
            val normalizedQuestionType = normalizeDocumentQuestionType(item.questionType, fileTypeLabel)
            val normalizedEvidenceKind = normalizeEvidenceKind(item.evidenceKind)
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            if (!isUsableDocumentQuestion(normalizedQuestion, normalizedQuestionType, normalizedEvidenceKind)) return@mapNotNull null

            val fingerprint = normalizedQuestion
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            if (!seen.add(fingerprint)) return@mapNotNull null

            if (!isAllowedDocumentQuestionType(normalizedQuestionType, fileTypeLabel, normalizedEvidenceKind)) return@mapNotNull null
            if (!isCompatibleQuestionForEvidenceKind(normalizedQuestion, normalizedQuestionType, normalizedEvidenceKind)) return@mapNotNull null

            val normalizedAnswer = item.referenceAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !isGuideLikeModelAnswer(it) &&
                        isDocumentAnswerLinkedToQuestion(it, normalizedQuestion, normalizedQuestionType) &&
                        isCompatibleReferenceAnswer(it, normalizedQuestionType, normalizedEvidenceKind)
                }
                ?: return@mapNotNull null

            val normalizedEvidence = item.evidence
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 8 }
                .distinct()
                .take(4)
            if (normalizedEvidence.isEmpty()) return@mapNotNull null

            item.copy(
                questionText = normalizedQuestion,
                questionType = normalizedQuestionType,
                evidenceKind = normalizedEvidenceKind,
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

    private fun parseGeneratedSkillTechQuestions(raw: String): List<GeneratedSkillTechQuestion> {
        val node = objectMapper.readTree(raw)
        return node["skills"]
            ?.takeIf { it.isArray }
            ?.flatMap { skillNode ->
                val skillName = skillNode.text("skillName").trim()
                skillNode["questions"]
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { item ->
                        val questionText = item.text("questionText").ifBlank { return@mapNotNull null }
                        GeneratedSkillTechQuestion(
                            skillName = skillName,
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

    private fun validateGeneratedSkillTechQuestions(
        generated: List<GeneratedSkillTechQuestion>,
        jobName: String,
        skillNames: List<String>
    ): List<GeneratedSkillTechQuestion> {
        val labelsBySkill = skillNames.associateBy(
            keySelector = { it.trim().lowercase() },
            valueTransform = {
                CategoryLabels(
                    jobLabel = jobName.trim().ifBlank { "직무" },
                    skillLabel = it
                )
            }
        )
        val seen = linkedSetOf<String>()
        return generated.mapNotNull { item ->
            val normalizedSkillName = item.skillName.trim().lowercase()
            val labels = labelsBySkill[normalizedSkillName] ?: return@mapNotNull null
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            if (!isUsableTechQuestion(normalizedQuestion)) return@mapNotNull null
            val fingerprint = "${normalizedSkillName}|${
                normalizedQuestion.lowercase().replace(Regex("[^a-z0-9가-힣]+"), "")
            }"
            if (!seen.add(fingerprint)) return@mapNotNull null

            val normalizedAnswer = item.canonicalAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isGuideLikeModelAnswer(it) }
                ?: return@mapNotNull null

            item.copy(
                skillName = labels.skillLabel,
                questionText = normalizedQuestion,
                canonicalAnswer = normalizedAnswer,
                tags = normalizeTechTags(item.tags, labels)
            )
        }
    }

    private fun isUsableDocumentQuestion(questionText: String, questionType: String, evidenceKind: String): Boolean {
        if (questionText.isBlank()) return false
        val introQuestion = questionType.startsWith("INTRODUCE_")
        if (questionText.length < if (introQuestion) 12 else 16) return false
        if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED|DEVOPS|DATA|AI|ML|CLOUD|SECURITY)\\b").containsMatchIn(questionText)) return false
        val lowered = questionText.lowercase()
        val banned = listOf(
            "어떤 기술에도",
            "일반적으로",
            "일반적인",
            "상식적으로",
            "포괄적으로",
            "보편적으로",
            "전반적으로",
            "보통",
            "대체로",
            "대부분",
            "in general",
            "generally",
            "overall",
            "broadly speaking",
            "for any technology",
            "most cases"
        )
        if (banned.any { lowered.contains(it) }) return false
        val tokens = questionText
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
        if (tokens.size < if (introQuestion) 3 else 4) return false
        val commonDomainHints = listOf(
            "프로젝트", "서비스", "사용자", "구현", "설계", "개선", "경험", "선택", "이유",
            "협업", "성능", "트러블슈팅", "문제", "해결", "운영", "개발", "아키텍처",
            "project", "service", "user", "implementation", "design", "improve", "experience",
            "decision", "reason", "collaboration", "performance", "troubleshooting", "problem",
            "solution", "operation", "development", "architecture", "result", "outcome"
        )
        val introduceHints = listOf(
            "지원", "동기", "포부", "가치", "가치관", "기준", "관점", "태도", "실천", "계획", "입사",
            "motivation", "value", "principle", "mindset", "plan", "goal", "future", "join", "apply"
        )
        val domainHints = if (introQuestion || evidenceKind in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE")) {
            commonDomainHints + introduceHints
        } else {
            commonDomainHints
        }
        if (domainHints.none { lowered.contains(it) }) return false
        if (!questionText.trim().endsWith("?")) return false
        return true
    }

    private fun documentQuestionTypeRules(fileTypeKey: String): List<String> {
        return when (fileTypeKey) {
            "RESUME" -> listOf(
                "- RESUME 문서는 questionType으로 RESUME_EXPERIENCE 또는 RESUME_RESULT만 사용",
                "- 이력서 발췌가 실제 업무/프로젝트/성과를 말하지 않으면 과거형 경험 검증 질문으로 비약하지 말 것"
            )
            "PORTFOLIO" -> listOf(
                "- PORTFOLIO 문서는 questionType으로 PORTFOLIO_PROJECT, PORTFOLIO_RESULT, PORTFOLIO_DECISION 중 하나만 사용",
                "- 포트폴리오 질문은 문제 해결, 기술 선택, 구현 책임, 결과를 묻되 문서에 없는 리더십/운영 범위를 지어내지 말 것"
            )
            "INTRODUCE" -> listOf(
                "- INTRODUCE 문서는 questionType으로 INTRODUCE_MOTIVATION, INTRODUCE_VALUE, INTRODUCE_FUTURE_PLAN, INTRODUCE_EXPERIENCE 중 하나만 사용",
                "- MOTIVATION_OR_ASPIRATION 또는 VALUE_OR_ATTITUDE 발췌에서는 INTRODUCE_MOTIVATION, INTRODUCE_VALUE, INTRODUCE_FUTURE_PLAN만 사용",
                "- ACTUAL_EXPERIENCE 또는 PROJECT_OR_RESULT 발췌에서만 INTRODUCE_EXPERIENCE를 사용할 수 있음"
            )
            else -> listOf("- 문서 유형과 일치하는 questionType만 사용")
        }
    }

    private fun documentQuestionPatternRules(fileTypeKey: String): List<String> {
        val commonRules = mutableListOf(
            "- 단순 나열형 질문 대신 이유, 역할, 의사결정, 결과를 묻는 면접형 질문 우선",
            "- 사용자의 실제 경험을 단정하지 말고, 문서 맥락을 바탕으로 한 설득력 있는 질문과 예시 답변을 작성할 것"
        )

        return when (fileTypeKey) {
            "INTRODUCE" -> commonRules + listOf(
                "- 자기소개서의 미래지향적 문장, 포부, 마음가짐, 가치관을 이미 수행한 경험처럼 단정하여 질문하지 말 것",
                "- MOTIVATION_OR_ASPIRATION 발췌에서는 왜 그런 관점을 갖게 되었는지, 입사 후 어떻게 적용할지, 어떤 기준을 중요하게 보는지 묻는 질문을 우선",
                "- VALUE_OR_ATTITUDE 발췌에서는 판단 기준, 협업 원칙, 일하는 방식, 우선순위 기준을 묻는 질문을 우선",
                "- ACTUAL_EXPERIENCE 또는 PROJECT_OR_RESULT 발췌가 명시적으로 있을 때만 어떻게 개선했는지, 어떤 기준으로 판단했는지, 결과와 리스크를 어떻게 관리했는지 묻는 행동형 질문 허용",
                "- 동기/가치관형 referenceAnswer는 STAR를 억지로 맞추지 말고 동기, 근거 경험, 실제 적용 계획이 자연스럽게 드러나게 작성"
            )
            else -> commonRules + listOf(
                "- ACTUAL_EXPERIENCE와 PROJECT_OR_RESULT 발췌에서는 referenceAnswer를 STAR형 예시 답변으로 작성하고 상황/과제/행동/결과가 자연스럽게 드러나게 할 것"
            )
        }
    }

    private fun normalizeDocumentFileType(fileTypeLabel: String): String {
        return when (fileTypeLabel.trim().uppercase()) {
            "RESUME", "이력서" -> "RESUME"
            "PORTFOLIO", "포트폴리오" -> "PORTFOLIO"
            "INTRODUCE", "자기소개서" -> "INTRODUCE"
            else -> fileTypeLabel.trim().uppercase()
        }
    }

    private fun normalizeDocumentQuestionType(questionType: String, fileTypeLabel: String): String {
        val normalized = questionType.trim().uppercase()
        if (normalized.isBlank()) return toDocumentQuestionType(fileTypeLabel)
        return normalized
    }

    private fun normalizeEvidenceKind(evidenceKind: String): String {
        return when (evidenceKind.trim().uppercase()) {
            "ACTUAL_EXPERIENCE",
            "PROJECT_OR_RESULT",
            "MOTIVATION_OR_ASPIRATION",
            "VALUE_OR_ATTITUDE" -> evidenceKind.trim().uppercase()
            else -> "ACTUAL_EXPERIENCE"
        }
    }

    private fun defaultEvidenceKind(fileTypeLabel: String): String {
        return when (normalizeDocumentFileType(fileTypeLabel)) {
            "INTRODUCE" -> "MOTIVATION_OR_ASPIRATION"
            "PORTFOLIO" -> "PROJECT_OR_RESULT"
            else -> "ACTUAL_EXPERIENCE"
        }
    }

    private fun isAllowedDocumentQuestionType(questionType: String, fileTypeLabel: String, evidenceKind: String): Boolean {
        val fileTypeKey = normalizeDocumentFileType(fileTypeLabel)
        if (!questionType.startsWith("${fileTypeKey}_")) return false

        return when (fileTypeKey) {
            "INTRODUCE" -> when (evidenceKind) {
                "MOTIVATION_OR_ASPIRATION" -> questionType in setOf("INTRODUCE_MOTIVATION", "INTRODUCE_FUTURE_PLAN")
                "VALUE_OR_ATTITUDE" -> questionType in setOf("INTRODUCE_VALUE", "INTRODUCE_MOTIVATION")
                "ACTUAL_EXPERIENCE", "PROJECT_OR_RESULT" -> questionType in setOf("INTRODUCE_EXPERIENCE", "INTRODUCE_MOTIVATION")
                else -> false
            }
            "RESUME" -> questionType in setOf("RESUME_EXPERIENCE", "RESUME_RESULT")
            "PORTFOLIO" -> questionType in setOf("PORTFOLIO_PROJECT", "PORTFOLIO_RESULT", "PORTFOLIO_DECISION")
            else -> true
        }
    }

    private fun isCompatibleQuestionForEvidenceKind(questionText: String, questionType: String, evidenceKind: String): Boolean {
        if (evidenceKind !in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE")) return true
        if (questionType.endsWith("_EXPERIENCE") || questionType.endsWith("_PROJECT") || questionType.endsWith("_RESULT") || questionType.endsWith("_DECISION")) {
            return false
        }
        return !looksLikePastExecutionAssumption(questionText)
    }

    private fun isCompatibleReferenceAnswer(referenceAnswer: String, questionType: String, evidenceKind: String): Boolean {
        return if (evidenceKind in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE")) {
            !looksLikePastExecutionAssumption(referenceAnswer) && !questionType.endsWith("_EXPERIENCE")
        } else {
            true
        }
    }

    private fun looksLikePastExecutionAssumption(text: String): Boolean {
        val lowered = text.lowercase()
        val patterns = listOf(
            "어떻게 개선",
            "어떻게 관리",
            "어떻게 해결",
            "어떻게 줄였",
            "어떤 리스크",
            "how did you improve",
            "how did you manage",
            "what risks did you face",
            "how did you reduce",
            "그 과정에서",
            "당시 발생할 수 있는 리스크",
            "what happened during",
            "during that process"
        )
        return patterns.any { lowered.contains(it) }
    }

    private fun documentQuestionTypeRequiresStar(questionType: String?): Boolean {
        val normalized = questionType?.trim()?.uppercase().orEmpty()
        if (normalized.isBlank()) return true
        return normalized !in setOf("INTRODUCE_MOTIVATION", "INTRODUCE_VALUE", "INTRODUCE_FUTURE_PLAN")
    }

    private fun isDocumentAnswerLinkedToQuestion(answer: String, questionText: String, questionType: String): Boolean {
        val answerTokens = answer.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()
        if (answerTokens.size < 6) return false

        val questionTokens = questionText.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()

        val overlap = questionTokens.intersect(answerTokens).size
        if (questionType in setOf("INTRODUCE_MOTIVATION", "INTRODUCE_VALUE", "INTRODUCE_FUTURE_PLAN")) {
            if (overlap >= 1) return true
            val motivationMarkers = listOf("동기", "가치", "기준", "계획", "포부", "motivation", "value", "plan", "goal")
            return motivationMarkers.any { marker ->
                questionText.contains(marker, ignoreCase = true) && answer.contains(marker, ignoreCase = true)
            }
        }
        return overlap >= 2
    }

    private fun isRateLimitError(ex: Exception): Boolean {
        val message = ex.message?.lowercase().orEmpty()
        return "http 429" in message ||
            "http 503" in message ||
            "resource_exhausted" in message ||
            "quota exceeded" in message ||
            "rate limit" in message
    }

    private fun shouldStopRetry(ex: Exception): Boolean {
        return ex is GeminiTransientException ||
            ex is AiProviderAuthorizationException ||
            isRateLimitError(ex)
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
            trimmed.startsWith("Question intent") ||
            trimmed.startsWith("A strong answer") ||
            trimmed.startsWith("Key points") ||
            trimmed.contains("답변해") ||
            trimmed.contains("설명해야")
    }

    fun localizeInterviewText(
        text: String?,
        language: InterviewLanguage,
        contentType: String
    ): String? {
        val source = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (language == InterviewLanguage.KO) return source
        if (looksMostlyEnglish(source)) return source

        val prompt = """
            You are a localization assistant for interview sessions.
            Translate the following $contentType into natural, professional English.
            Preserve technical terms, product names, numbers, and factual meaning.
            Return JSON only.

            {
              "text": "translated text"
            }

            [source]
            $source
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.2)
            objectMapper.readTree(generated.text)["text"]?.asText()?.trim().takeIf { !it.isNullOrBlank() } ?: source
        }.onFailure { ex ->
            logger.warn("인터뷰 텍스트 현지화 실패(language={}, type={}): {}", language, contentType, ex.message)
        }.getOrDefault(source)
    }

    fun localizeTurnContent(
        questionText: String,
        modelAnswer: String?,
        evidence: List<String>,
        language: InterviewLanguage
    ): LocalizedInterviewContent {
        val normalizedQuestion = questionText.trim()
        val normalizedModelAnswer = modelAnswer?.trim()?.takeIf { it.isNotBlank() }
        val normalizedEvidence = evidence.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        if (language == InterviewLanguage.KO) {
            return LocalizedInterviewContent(normalizedQuestion, normalizedModelAnswer, normalizedEvidence)
        }
        if (looksMostlyEnglish(normalizedQuestion) &&
            (normalizedModelAnswer == null || looksMostlyEnglish(normalizedModelAnswer)) &&
            normalizedEvidence.all(::looksMostlyEnglish)
        ) {
            return LocalizedInterviewContent(normalizedQuestion, normalizedModelAnswer, normalizedEvidence)
        }

        val prompt = """
            You are a localization assistant for interview sessions.
            Translate the following interview content into natural, professional English.
            Preserve technical terms, product names, numbers, and factual meaning.
            Return JSON only.

            {
              "questionText": "translated question",
              "modelAnswer": "translated model answer or empty string",
              "evidence": ["translated evidence", "..."]
            }

            [question]
            $normalizedQuestion

            [modelAnswer]
            ${normalizedModelAnswer ?: ""}

            [evidence]
            ${if (normalizedEvidence.isEmpty()) "(empty)" else normalizedEvidence.joinToString("\n- ", prefix = "- ")}
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.2)
            val node = objectMapper.readTree(generated.text)
            LocalizedInterviewContent(
                questionText = node["questionText"]?.asText()?.trim().takeIf { !it.isNullOrBlank() } ?: normalizedQuestion,
                modelAnswer = node["modelAnswer"]?.asText()?.trim().takeIf { !it.isNullOrBlank() } ?: normalizedModelAnswer,
                evidence = node["evidence"]
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
                    ?: normalizedEvidence
            )
        }.onFailure { ex ->
            logger.warn("인터뷰 턴 현지화 실패(language={}): {}", language, ex.message)
        }.getOrDefault(
            LocalizedInterviewContent(
                questionText = normalizedQuestion,
                modelAnswer = normalizedModelAnswer,
                evidence = normalizedEvidence
            )
        )
    }

    fun localizeTurnContents(
        items: List<TurnContentLocalizationRequest>,
        language: InterviewLanguage
    ): Map<String, LocalizedInterviewContent> {
        if (items.isEmpty()) return emptyMap()
        val normalized = items.associate { item ->
            item.key to LocalizedInterviewContent(
                questionText = item.questionText.trim(),
                modelAnswer = item.modelAnswer?.trim()?.takeIf { it.isNotBlank() },
                evidence = item.evidence.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            )
        }
        if (language == InterviewLanguage.KO) return normalized

        val pending = normalized.filterValues { content ->
            !looksMostlyEnglish(content.questionText) ||
                (content.modelAnswer != null && !looksMostlyEnglish(content.modelAnswer)) ||
                content.evidence.any { !looksMostlyEnglish(it) }
        }
        if (pending.isEmpty()) return normalized

        val prompt = """
            You are a localization assistant for interview sessions.
            Translate each interview turn item into natural, professional English.
            Preserve technical terms, product names, numbers, and factual meaning.
            Return JSON only.

            {
              "items": [
                {
                  "key": "stable key",
                  "questionText": "translated question",
                  "modelAnswer": "translated model answer or empty string",
                  "evidence": ["translated evidence", "..."]
                }
              ]
            }

            [items]
            ${objectMapper.writeValueAsString(
                pending.map { (key, content) ->
                    mapOf(
                        "key" to key,
                        "questionText" to content.questionText,
                        "modelAnswer" to content.modelAnswer.orEmpty(),
                        "evidence" to content.evidence
                    )
                }
            )}
        """.trimIndent()

        val localized = runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.2)
            objectMapper.readTree(generated.text)["items"]
                ?.takeIf { it.isArray }
                ?.mapNotNull { item ->
                    val key = item["key"]?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    key to LocalizedInterviewContent(
                        questionText = item["questionText"]?.asText()?.trim().takeIf { !it.isNullOrBlank() }
                            ?: normalized[key]?.questionText
                            ?: return@mapNotNull null,
                        modelAnswer = item["modelAnswer"]?.asText()?.trim().takeIf { !it.isNullOrBlank() }
                            ?: normalized[key]?.modelAnswer,
                        evidence = item["evidence"]
                            ?.takeIf { it.isArray }
                            ?.mapNotNull { evidenceItem -> evidenceItem.asText().trim().takeIf(String::isNotBlank) }
                            ?: normalized[key]?.evidence
                            ?: emptyList()
                    )
                }
                ?.toMap()
                .orEmpty()
        }.onFailure { ex ->
            logger.warn("인터뷰 턴 배치 현지화 실패(language={}, count={}): {}", language, pending.size, ex.message)
        }.getOrDefault(emptyMap())

        return normalized + localized
    }

    fun localizedIntroQuestion(language: InterviewLanguage): String {
        return when (language) {
            InterviewLanguage.KO -> "자기소개 부탁드리겠습니다."
            InterviewLanguage.EN -> "Please introduce yourself."
        }
    }

    private fun evaluationSystemRole(language: InterviewLanguage, englishRole: String): String {
        return when (language) {
            InterviewLanguage.KO -> "당신은 면접 답변을 평가하는 면접관입니다."
            InterviewLanguage.EN -> "You are an $englishRole."
        }
    }

    private fun generationSystemRole(language: InterviewLanguage, englishRole: String): String {
        return when (language) {
            InterviewLanguage.KO -> "당신은 면접 질문을 생성하는 면접관입니다."
            InterviewLanguage.EN -> "You are a $englishRole."
        }
    }

    private fun jsonLanguageInstruction(language: InterviewLanguage): String {
        return when (language) {
            InterviewLanguage.KO -> "아래 입력을 바탕으로 한국어 JSON만 출력하세요."
            InterviewLanguage.EN -> "Read the input below and return JSON only. feedback, bestPractice, and evidence must be written in English."
        }
    }

    private fun englishCommunicationRule(language: InterviewLanguage): String {
        return when (language) {
            InterviewLanguage.KO -> ""
            InterviewLanguage.EN -> "- communication 점수에는 grammar, sentence completeness, clarity, and natural professional English quality를 반영할 것"
        }
    }

    private fun emptyLocalizedPlaceholder(language: InterviewLanguage, contentType: String): String {
        return when (language) {
            InterviewLanguage.KO -> when (contentType) {
                "reference answer" -> "(참고 답안 없음)"
                "evidence" -> "(근거 없음)"
                else -> "(없음)"
            }
            InterviewLanguage.EN -> when (contentType) {
                "reference answer" -> "(no reference answer)"
                "evidence" -> "(no evidence)"
                else -> "(empty)"
            }
        }
    }

    private fun InterviewLanguage.displayLanguageName(): String = when (this) {
        InterviewLanguage.KO -> "Korean"
        InterviewLanguage.EN -> "English"
    }

    private fun looksMostlyEnglish(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val asciiLetters = letters.count { it.code in 65..90 || it.code in 97..122 }
        return asciiLetters >= letters.length * 0.7
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
        return parseEvaluationNode(objectMapper.readTree(raw))
    }

    private fun parseBatchEvaluationJson(raw: String): Map<String, AiTurnEvaluation> {
        val root = objectMapper.readTree(raw)
        return root["items"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val key = item["key"]?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to parseEvaluationNode(item)
            }
            ?.toMap()
            .orEmpty()
    }

    private fun parseEvaluationNode(node: JsonNode): AiTurnEvaluation {
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

data class LocalizedInterviewContent(
    val questionText: String,
    val modelAnswer: String?,
    val evidence: List<String> = emptyList()
)

data class TurnContentLocalizationRequest(
    val key: String,
    val questionText: String,
    val modelAnswer: String?,
    val evidence: List<String> = emptyList()
)

data class BatchTurnEvaluationInput(
    val key: String,
    val kind: String,
    val answerLanguage: String,
    val questionText: String,
    val questionType: String? = null,
    val referenceAnswer: String? = null,
    val evidence: List<String> = emptyList(),
    val userAnswer: String
)

data class GeneratedDocumentQuestion(
    val questionNo: Int,
    val questionText: String,
    val questionType: String,
    val evidenceKind: String,
    val referenceAnswer: String?,
    val evidence: List<String>
)

data class GeneratedTechQuestion(
    val questionText: String,
    val canonicalAnswer: String? = null,
    val tags: List<String> = emptyList()
)

data class GeneratedSkillTechQuestion(
    val skillName: String,
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
