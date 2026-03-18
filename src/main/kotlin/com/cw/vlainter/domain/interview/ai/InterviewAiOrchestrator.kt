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
import kotlin.math.roundToInt

@Component
class InterviewAiOrchestrator(
    private val aiProperties: AiProperties,
    private val llmProviderRouter: LlmProviderRouter,
    private val objectMapper: ObjectMapper
) {
    private val commonDomainHints = listOf(
        "프로젝트", "서비스", "사용자", "구현", "설계", "개선", "경험", "선택", "이유",
        "협업", "성능", "트러블슈팅", "문제", "해결", "운영", "개발", "아키텍처",
        "project", "service", "user", "implementation", "design", "improve", "experience",
        "decision", "reason", "collaboration", "performance", "troubleshooting", "problem",
        "solution", "operation", "development", "architecture", "result", "outcome"
    )
    private val introduceHints = listOf(
        "지원", "동기", "포부", "가치", "가치관", "기준", "관점", "태도", "실천", "계획", "입사",
        "motivation", "value", "principle", "mindset", "plan", "goal", "future", "join", "apply"
    )
    private val resumeHints = listOf(
        "경력", "직무", "인턴", "수상", "연구", "강점", "활동", "지원", "성장", "도전",
        "대외활동", "학부연구생", "실무", "업무", "회사", "지원서",
        "career", "internship", "award", "strength", "responsibility", "work", "company"
    )
    private val interviewQuestionEndings = listOf(
        "설명해 주세요",
        "말씀해 주세요",
        "얘기해 주세요",
        "이야기해 주세요",
        "알려 주세요",
        "공유해 주세요",
        "정리해 주세요",
        "설명해주세요",
        "말씀해주세요",
        "얘기해주세요",
        "이야기해주세요",
        "알려주세요",
        "공유해주세요",
        "정리해주세요",
        "무엇인가요",
        "왜 그런가요",
        "어떻게 생각하시나요",
        "어떻게 보시나요",
        "해주실 수 있나요",
        "말해주실 수 있나요",
        "설명해주실 수 있나요"
    )
    private val duplicateQuestionStopTokens = setOf(
        "무엇", "무엇인가요", "무엇인가", "어떤", "어떻게", "왜", "이유", "설명", "설명해", "설명해주세요",
        "설명해주실", "말씀", "말씀해", "말씀해주세요", "말해", "알려", "공유", "정리", "당시", "본인",
        "해당", "가장", "중점", "부분", "선택", "핵심", "역할", "기여", "프로젝트", "서비스", "기술",
        "기능", "구현", "무엇인지", "무엇인지요", "생각", "보시나요", "해주실", "수", "있나요",
        "what", "which", "why", "how", "explain", "describe", "tell", "share", "role", "responsibility",
        "responsibilities", "contribution", "contributions", "focus", "focused", "reason", "reasons",
        "choice", "choices", "project", "service", "technical", "implementation"
    )
    private val roleIntentHints = setOf("역할", "기여", "담당", "책임", "맡은", "role", "contribution", "responsibility")
    private val reasonIntentHints = setOf("이유", "왜", "선택", "판단", "기준", "reason", "why", "choice", "decision")
    private val focusIntentHints = setOf("중점", "우선", "집중", "가장 중요", "focus", "priority", "important")
    private val resultIntentHints = setOf("성과", "결과", "효과", "개선", "impact", "result", "outcome", "improvement")
    private val challengeIntentHints = setOf("문제", "난관", "도전", "어려움", "해결", "트러블", "challenge", "problem", "issue", "solve")
    private val collaborationIntentHints = setOf("협업", "조율", "갈등", "커뮤니케이션", "collaboration", "communication", "conflict")
    private val courseMaterialSummaryRetryDelaysMs = listOf(400L, 900L)
    private val preservedTermPhraseRegex = Regex("""\b[A-Za-z][A-Za-z0-9+/#()\-]*(?:\s+[A-Za-z][A-Za-z0-9+/#()\-]*){0,3}\b""")
    private val preservedSingleWordStopwords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "into", "is", "of", "on", "or",
        "that", "the", "to", "via", "with", "without"
    )
    private val technicalTermGlossary = listOf(
        TechnicalTermGlossaryEntry("Architecture", listOf("아키텍처")),
        TechnicalTermGlossaryEntry("Mechanism", listOf("메커니즘")),
        TechnicalTermGlossaryEntry("Transformer", listOf("트랜스포머", "트랜스 포머")),
        TechnicalTermGlossaryEntry("Attention", listOf("어텐션")),
        TechnicalTermGlossaryEntry("Seq2Seq", listOf("시퀀스 투 시퀀스", "시퀀스투시퀀스", "시퀀스-투-시퀀스")),
        TechnicalTermGlossaryEntry("Encoder", listOf("인코더")),
        TechnicalTermGlossaryEntry("Decoder", listOf("디코더")),
        TechnicalTermGlossaryEntry("Query", listOf("쿼리")),
        TechnicalTermGlossaryEntry("Key", listOf("키")),
        TechnicalTermGlossaryEntry("Value", listOf("밸류", "값 벡터")),
        TechnicalTermGlossaryEntry("Context Vector", listOf("컨텍스트 벡터", "문맥 벡터")),
        TechnicalTermGlossaryEntry("Alignment Score", listOf("얼라이먼트 스코어", "정렬 점수")),
        TechnicalTermGlossaryEntry("Softmax", listOf("소프트맥스")),
        TechnicalTermGlossaryEntry("Positional Encoding", listOf("포지셔널 인코딩", "위치 인코딩")),
        TechnicalTermGlossaryEntry("Multi-Head Attention", listOf("멀티 헤드 어텐션", "멀티헤드 어텐션")),
        TechnicalTermGlossaryEntry("Masked Multi-Head Attention", listOf("마스크드 멀티 헤드 어텐션", "마스크드 멀티헤드 어텐션")),
        TechnicalTermGlossaryEntry("Encoder-Decoder Attention", listOf("인코더-디코더 어텐션", "인코더 디코더 어텐션")),
        TechnicalTermGlossaryEntry("Scaled Dot-Product Attention", listOf("스케일드 닷 프로덕트 어텐션", "스케일드 닷프로덕트 어텐션")),
        TechnicalTermGlossaryEntry("Dot Product Attention", listOf("닷 프로덕트 어텐션", "닷프로덕트 어텐션")),
        TechnicalTermGlossaryEntry("Feed Forward Network", listOf("피드 포워드 네트워크", "피드포워드 네트워크")),
        TechnicalTermGlossaryEntry("Residual Connection", listOf("레지듀얼 커넥션", "잔차 연결")),
        TechnicalTermGlossaryEntry("Layer Normalization", listOf("레이어 노말라이제이션", "레이어 정규화")),
        TechnicalTermGlossaryEntry("Linear Layer", listOf("리니어 레이어", "선형 레이어")),
        TechnicalTermGlossaryEntry("Padding Mask", listOf("패딩 마스크")),
        TechnicalTermGlossaryEntry("Beam Search", listOf("빔 서치")),
        TechnicalTermGlossaryEntry("EOS token", listOf("EOS 토큰", "종료 토큰"))
    )

    private val logger = LoggerFactory.getLogger(javaClass)

    data class DocumentQuestionValidationResult(
        val accepted: List<GeneratedDocumentQuestion>,
        val rejectedReasons: List<String>
    )

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
            val candidateCount = documentQuestionCandidateCount(remaining, questionCount)
            val prompt = buildDocumentQuestionPrompt(fileTypeLabel, difficulty, candidateCount, contextSnippets, language)
            try {
                val generated = llmProviderRouter.generateJson(prompt, temperature = temperature)
                val parsed = parseGeneratedDocumentQuestions(generated.text, fileTypeLabel)
                val validation = validateGeneratedDocumentQuestions(
                    generated = parsed,
                    fileTypeLabel = fileTypeLabel,
                    existingAccepted = collected.values.toList()
                )
                if (validation.rejectedReasons.isNotEmpty()) {
                    logger.info(
                        "document question validation summary fileType={} round={} accepted={} rejected={} reasons={}",
                        fileTypeLabel,
                        round + 1,
                        validation.accepted.size,
                        validation.rejectedReasons.size,
                        summarizeRejectedReasons(validation.rejectedReasons)
                    )
                }
                validation.accepted.forEach { item ->
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
        if (collected.isNotEmpty() && canReturnPartialDocumentQuestions(lastError)) {
            logger.info(
                "document question generation partial success fileType={} requested={} generated={}",
                fileTypeLabel,
                questionCount,
                collected.size
            )
            return collected.values.toList()
        }
        if (lastError is GeminiTransientException) {
            throw lastError
        }

        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "생성된 문서 질문/모범답안이 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
    }

    fun generateCourseExamQuestions(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        questionCount: Int,
        difficultyLevel: Int?,
        questionStyles: List<String>,
        lectureContextSnippets: List<String>,
        styleReferenceSnippets: List<String>,
        generationMode: String,
        language: InterviewLanguage = InterviewLanguage.KO
    ): List<GeneratedCourseExamQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val requestedStyleSet = questionStyles
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()
        validateCourseExamGenerationInputs(
            requestedStyleSet = requestedStyleSet,
            generationMode = generationMode,
            lectureContextSnippets = lectureContextSnippets,
            styleReferenceSnippets = styleReferenceSnippets
        )
        val temperatures = listOf(0.35, 0.5, 0.65, 0.8, 0.95)
        val collected = linkedMapOf<String, GeneratedCourseExamQuestion>()
        var lastError: Exception? = null

        for ((index, temperature) in temperatures.withIndex()) {
            if (collected.size >= questionCount) break
            val remaining = questionCount - collected.size
            val existingQuestionTexts = collected.values.map { it.questionText }
            logger.info(
                "과목 시험문제 생성 라운드 시작 course={} mode={} round={} remaining={} requestedStyles={} excludedCount={} temperature={}",
                courseName,
                generationMode,
                index + 1,
                remaining,
                questionStyles.joinToString(","),
                existingQuestionTexts.size,
                temperature
            )
            val prompt = buildCourseExamQuestionPrompt(
                universityName = universityName,
                departmentName = departmentName,
                courseName = courseName,
                professorName = professorName,
                difficultyLevel = difficultyLevel,
                questionStyles = questionStyles,
                questionCount = minOf(questionCount + 2, remaining + 2),
                lectureContextSnippets = lectureContextSnippets,
                styleReferenceSnippets = styleReferenceSnippets,
                generationMode = generationMode,
                excludedQuestionTexts = existingQuestionTexts,
                language = language
            )
            try {
                val generated = llmProviderRouter.generateJson(prompt, temperature = temperature)
                val parsed = parseGeneratedCourseExamQuestions(generated.text)
                val beforeCount = collected.size
                var rejectedBlankCount = 0
                var rejectedUnrequestedStyleCount = 0
                var rejectedGenericCount = 0
                parsed.forEach { item ->
                    val normalized = normalizeStructuredText(item.questionText)
                    val normalizedAnswer = normalizeStructuredText(item.canonicalAnswer)
                    val normalizedCriteria = normalizeStructuredText(item.gradingCriteria)
                    if (normalized.isBlank() || normalizedAnswer.isBlank() || normalizedCriteria.isBlank()) {
                        rejectedBlankCount += 1
                        return@forEach
                    }
                    val normalizedStyle = item.questionStyle.trim().uppercase()
                    if (requestedStyleSet.isNotEmpty() && normalizedStyle !in requestedStyleSet) {
                        rejectedUnrequestedStyleCount += 1
                        return@forEach
                    }
                    val key = normalized.lowercase()
                    if (!collected.containsKey(key) && isUsableCourseExamQuestion(courseName, normalized)) {
                        collected[key] = item.copy(
                            questionNo = collected.size + 1,
                            questionText = normalized,
                            questionStyle = normalizedStyle,
                            canonicalAnswer = normalizedAnswer,
                            gradingCriteria = normalizedCriteria,
                            referenceExample = item.referenceExample?.trim()?.takeIf { it.isNotBlank() }
                        )
                    } else if (!collected.containsKey(key)) {
                        rejectedGenericCount += 1
                    }
                }
                logger.info(
                    "과목 시험문제 생성 라운드 결과 course={} mode={} round={} parsedCount={} addedCount={} cumulativeCount={} rejectedBlank={} rejectedUnrequestedStyle={} rejectedGeneric={}",
                    courseName,
                    generationMode,
                    index + 1,
                    parsed.size,
                    collected.size - beforeCount,
                    collected.size,
                    rejectedBlankCount,
                    rejectedUnrequestedStyleCount,
                    rejectedGenericCount
                )
            } catch (ex: Exception) {
                lastError = ex
                logger.warn(
                    "과목 시험문제 생성 실패(provider={}, round={}, remaining={}, mode={}): {}",
                    aiProperties.provider,
                    index + 1,
                    remaining,
                    generationMode,
                    ex.message
                )
                if (shouldStopRetry(ex)) {
                    logger.warn("과목 시험문제 생성 재시도 중단: transient overload/rate limit 감지")
                    break
                }
            }
        }

        if (collected.size >= questionCount) {
            return collected.values.take(questionCount).toList()
        }
        if (collected.isNotEmpty() && canReturnPartialDocumentQuestions(lastError)) {
            logger.info(
                "course exam question generation partial success course={} requested={} generated={} mode={}",
                courseName,
                questionCount,
                collected.size,
                generationMode
            )
            return collected.values.toList()
        }
        val transientError = lastError as? GeminiTransientException
        if (transientError != null) {
            throw transientError
        }

        val cause = lastError?.message?.takeIf { it.isNotBlank() }
        throw IllegalStateException(
            "시험문제 생성 결과가 품질 기준을 충족하지 못했습니다. 잠시 후 다시 시도해 주세요.${cause?.let { " ($it)" } ?: ""}"
        )
    }

    fun generateCourseMaterialSummary(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        sources: List<CourseMaterialSummarySource>,
        language: InterviewLanguage = InterviewLanguage.KO
    ): GeneratedCourseMaterialSummary {
        require(sources.isNotEmpty()) { "sources must not be empty." }
        val preservedTerms = extractPreservedTerms(sources)
        val temperatures = listOf(0.2, 0.2, 0.2)
        var lastError: Exception? = null
        for ((index, temperature) in temperatures.withIndex()) {
            try {
                val outlinePrompt = buildCourseMaterialSummaryOutlinePrompt(
                    universityName = universityName,
                    departmentName = departmentName,
                    courseName = courseName,
                    professorName = professorName,
                    sources = sources,
                    preservedTerms = preservedTerms,
                    language = language
                )
                val outline = parseCourseMaterialSummaryOutline(
                    llmProviderRouter.generateJson(
                        outlinePrompt,
                        temperature = 0.1,
                        maxOutputTokens = 4096
                    ).text
                )
                val prompt = buildCourseMaterialSummaryPrompt(
                    universityName = universityName,
                    departmentName = departmentName,
                    courseName = courseName,
                    professorName = professorName,
                    sources = sources,
                    outline = outline,
                    preservedTerms = preservedTerms,
                    language = language
                )
                val generated = llmProviderRouter.generateJson(
                    prompt,
                    temperature = temperature,
                    maxOutputTokens = 8192
                )
                return parseGeneratedCourseMaterialSummary(generated.text)
                    .normalizeTechnicalTerminology(preservedTerms)
                    .also {
                        validatePreservedTerminology(it, preservedTerms)
                        validateCourseMaterialSummaryDensity(it)
                }
            } catch (ex: Exception) {
                lastError = ex
                if (!shouldRetryCourseMaterialSummary(ex) || index == temperatures.lastIndex) {
                    throw ex
                }
                val delayMillis = courseMaterialSummaryRetryDelaysMs[index]
                logger.warn(
                    "강의자료 요약 생성 재시도(provider={}, round={}, delayMs={}): {}",
                    aiProperties.provider,
                    index + 1,
                    delayMillis,
                    ex.message
                )
                try {
                    Thread.sleep(delayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
        throw lastError ?: IllegalStateException("강의자료 요약 생성에 실패했습니다.")
    }

    fun refineCourseTranscript(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        transcriptChunk: String,
        language: InterviewLanguage = InterviewLanguage.KO
    ): String {
        require(transcriptChunk.isNotBlank()) { "transcriptChunk must not be blank." }

        val professorLine = professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"
        val preservedTerms = extractPreservedTerms(
            listOf(CourseMaterialSummarySource(fileName = courseName, snippets = listOf(transcriptChunk)))
        )
        val payload = mapOf(
            "universityName" to universityName,
            "departmentName" to departmentName,
            "courseName" to courseName,
            "professorName" to professorLine,
            "transcriptChunk" to transcriptChunk.trim()
        )
        val prompt = """
            당신은 대학 강의 자동 생성 자막 후보정 도우미입니다.
            ${jsonObjectOnlyRule(language)}

            목표:
            - 자동 생성 자막의 띄어쓰기, 끊긴 문장, 반복 어절, 오탈자를 읽기 쉬운 문장으로 다듬는다.
            - 문맥상 명백한 연결만 복원한다.
            - 원문에 없는 개념, 공식, 예시, 결론을 새로 추가하지 않는다.
            - 불확실한 부분은 과장해서 보정하지 말고 최대한 보수적으로 유지한다.
            - 영어 전문용어, 약어, 수식, 함수명, 알고리즘명은 입력에 나온 표기를 그대로 유지한다.
            - 아래 원문 용어 유지 목록은 번역하거나 한글식 표기로 바꾸지 않는다.

            출력 JSON 스키마:
            {
              "refinedTranscript": "후보정된 자막 본문"
            }

            규칙:
            - 반드시 JSON 객체만 반환
            - 마크다운 코드블록 금지
            - 문단 구조는 필요 최소한으로만 정리
            - 군더더기 감탄사, 의미 없는 반복, 자막 잡음은 제거 가능
            - 강의 흐름과 순서는 유지
            - 원문 용어 유지 목록: ${if (preservedTerms.isEmpty()) "없음" else preservedTerms.joinToString(", ")}

            입력:
            ${objectMapper.writeValueAsString(payload)}
        """.trimIndent()

        val generated = llmProviderRouter.generateJson(prompt, temperature = 0.1)
        return parseRefinedCourseTranscript(generated.text)
    }

    fun refinePastExamPracticeQuestions(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        extractedQuestions: List<PastExamPracticeQuestionCandidate>,
        language: InterviewLanguage = InterviewLanguage.KO
    ): List<GeneratedCourseExamQuestion> {
        require(extractedQuestions.isNotEmpty()) { "extractedQuestions must not be empty." }

        val prompt = buildPastExamPracticeRefinementPrompt(
            universityName = universityName,
            departmentName = departmentName,
            courseName = courseName,
            professorName = professorName,
            extractedQuestions = extractedQuestions,
            language = language
        )
        val generated = llmProviderRouter.generateJson(prompt, temperature = 0.15)
        val parsed = parseGeneratedCourseExamQuestions(generated.text)
        if (parsed.size != extractedQuestions.size) {
            throw IllegalStateException(
                "족보 그대로 연습 문제 보정 결과 개수가 맞지 않습니다. extracted=${extractedQuestions.size}, refined=${parsed.size}"
            )
        }

        return parsed.mapIndexed { index, item ->
            val source = extractedQuestions[index]
            val correctedQuestion = normalizeStructuredText(item.questionText)
            item.copy(
                questionNo = source.questionNo,
                questionText = preservePastExamQuestionText(source.questionText, correctedQuestion),
                questionStyle = item.questionStyle.trim().uppercase(),
                canonicalAnswer = normalizeStructuredText(item.canonicalAnswer),
                gradingCriteria = normalizeStructuredText(item.gradingCriteria),
                referenceExample = item.referenceExample?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun recoverPastExamPracticeQuestionCandidates(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        sourceFileName: String,
        extractionMethod: String?,
        rawText: String,
        expectedQuestionCount: Int,
        language: InterviewLanguage = InterviewLanguage.KO
    ): List<String> {
        require(rawText.isNotBlank()) { "rawText must not be blank." }
        require(expectedQuestionCount > 0) { "expectedQuestionCount must be positive." }

        val prompt = buildPastExamPracticeRecoveryPrompt(
            universityName = universityName,
            departmentName = departmentName,
            courseName = courseName,
            professorName = professorName,
            sourceFileName = sourceFileName,
            extractionMethod = extractionMethod,
            rawText = rawText,
            expectedQuestionCount = expectedQuestionCount,
            language = language
        )
        val generated = llmProviderRouter.generateJson(prompt, temperature = 0.05)
        return parseRecoveredPastExamPracticeQuestions(generated.text)
    }

    fun evaluateCourseExamAnswersBatch(
        universityName: String,
        departmentName: String,
        courseName: String,
        generationMode: String,
        difficultyLevel: Int?,
        items: List<CourseExamEvaluationInput>,
        responseLanguage: InterviewLanguage = InterviewLanguage.KO
    ): Map<String, CourseExamEvaluationResult> {
        if (items.isEmpty()) return emptyMap()

        val prompt = """
            ${evaluationSystemRole(responseLanguage, "university written exam grader")}
            ${jsonLanguageInstruction(responseLanguage)}

            [대학교]
            $universityName

            [학과]
            $departmentName

            [과목명]
            $courseName

            [출제 모드]
            $generationMode

            [난이도]
            ${difficultyLevel ?: "족보 기준 반영"}

            아래 각 문제를 서로 독립적으로 채점하고 JSON만 반환하세요.

            출력 JSON 스키마:
            {
              "items": [
                {
                  "key": "stable key",
                  "score": 0~maxScore 정수,
                  "passScore": 통과 기준 점수 정수,
                  "feedback": "부분점수 근거와 보강 포인트를 포함한 총평(2~5문장)"
                }
              ]
            }

            채점 규칙:
            - 반드시 모든 입력 key를 유지해서 반환
            - score는 0 이상 maxScore 이하의 정수
            - 부분점수를 적극 허용할 것
            - difficultyLevel은 요구 사고 깊이를 판단하는 참고값일 뿐이며, 답안 형식이 모범답안과 다르다는 이유만으로 과도하게 감점하지 말 것
            - 학생 답안이 canonicalAnswer와 동일한 표현이 아니어도, 핵심 개념과 인과관계가 맞으면 점수를 인정할 것
            - 수식, 문장, bullet, 간단한 ASCII 흐름도 등 표현 방식의 차이만으로 감점하지 말 것
            - questionStyle=DEFINITION 또는 ESSAY: 핵심 개념의 정확성, 범위 충실도, 비교/적용 설명을 본다
            - questionStyle=CALCULATION: 식 설정, 변수 해석, 단위, 계산 과정, 최종 결론을 본다
            - questionStyle=CALCULATION: 정답 수식 자체를 쓰지 않았더라도 관계식과 풀이 논리를 말로 정확히 설명하면 상당한 부분점수를 줄 것
            - questionStyle=CALCULATION: 계산식 일부가 빠져도 핵심 접근법이 맞으면 0점을 주지 말 것
            - questionStyle=CODING: 답안이 실제 코드 형태인지, 요구 기능을 충족하는지, 예시 입출력 또는 제시된 조건을 만족하는지, 핵심 자료구조/알고리즘 선택이 적절한지 본다
            - questionStyle=CODING: 사용 언어는 감점 요소가 아니며, 학교 교육과정 차이를 고려해 Java, C, C++, Python 등 어떤 언어로 작성해도 로직이 타당하면 인정할 것
            - questionStyle=CODING: 컴파일 오류 가능성이 높은 문법 오류, 실행 자체가 불가능한 수준의 선언/구문 오류, 문제 요구를 깨는 API 오용은 명확한 감점 요소로 반영할 것
            - questionStyle=CODING: 단순 문법 실수 하나만으로 0점을 주지 말고, 로직이 맞으면 부분점수를 줄 것
            - questionStyle=PRACTICAL: 명령어, 절차, 시스템 조작 흐름의 정확성, 예제 만족 여부, 중요한 예외 처리를 본다
            - referenceExample이 있으면 예시 충족 여부를 함께 보되, 표현 차이만으로 감점하지 말 것
            - canonicalAnswer와 gradingCriteria는 채점 기준이며, 강의자료 밖 정답을 요구하지 말 것
            - 난이도가 높더라도 답안이 핵심 개념을 정확히 설명했다면 형식 부족만으로 대폭 감점하지 말 것
            - feedback에는 무엇을 맞췄고 무엇이 부족했는지, 왜 그 점수가 나왔는지 분명히 적을 것
            - feedback은 ${responseLanguage.displayLanguageName()}로 작성할 것
            - 반드시 JSON만 출력

            [items]
            ${objectMapper.writeValueAsString(items)}
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            parseCourseExamEvaluationJson(generated.text, items)
        }.onFailure { ex ->
            logger.warn("과목 시험문제 채점 실패(provider={}, count={}): {}", aiProperties.provider, items.size, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) emptyMap() else throw ex
        }
    }

    private fun documentQuestionCandidateCount(remaining: Int, targetCount: Int): Int {
        return when {
            remaining >= 3 -> minOf(targetCount + 2, remaining + 2)
            remaining == 2 -> minOf(targetCount + 1, remaining + 1)
            else -> remaining + 1
        }
    }

    private fun canReturnPartialDocumentQuestions(lastError: Exception?): Boolean {
        return when (lastError) {
            null -> true
            is AiProviderAuthorizationException -> false
            is GeminiTransientException -> lastError.statusCode == 429 || lastError.statusCode == 503
            else -> false
        }
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
            - questionType이 INTRODUCE_MOTIVATION, INTRODUCE_VALUE, INTRODUCE_FUTURE_PLAN, RESUME_MOTIVATION, RESUME_VALUE 인 DOCUMENT 항목은 STAR를 과도하게 강제하지 말고 동기, 판단 기준, 실제 적용 계획, 근거 연결성을 평가
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
            add("- 질문마다 primary evidence는 가능하면 서로 다른 문서 발췌를 사용하고, 같은 발췌를 재표현한 질문을 여러 개 만들지 말 것")
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
                  "questionType": "RESUME_EXPERIENCE | RESUME_RESULT | RESUME_MOTIVATION | RESUME_VALUE | PORTFOLIO_PROJECT | PORTFOLIO_RESULT | INTRODUCE_MOTIVATION | INTRODUCE_VALUE | INTRODUCE_FUTURE_PLAN | INTRODUCE_EXPERIENCE",
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

    private fun buildCourseExamQuestionPrompt(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        difficultyLevel: Int?,
        questionStyles: List<String>,
        questionCount: Int,
        lectureContextSnippets: List<String>,
        styleReferenceSnippets: List<String>,
        generationMode: String,
        excludedQuestionTexts: List<String> = emptyList(),
        language: InterviewLanguage
    ): String {
        val joinedLectureContext = lectureContextSnippets
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val joinedStyleReference = styleReferenceSnippets
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val normalizedMode = generationMode.trim().uppercase()
        val normalizedStyles = questionStyles.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val styleText = normalizedStyles.joinToString(", ").ifBlank { "DEFINITION" }
        val lectureSectionTitle = if (normalizedMode == "PAST_EXAM_PRACTICE") "강의 자료 발췌 (사용 금지)" else "강의 자료 발췌"
        val lectureSectionBody = if (normalizedMode == "PAST_EXAM_PRACTICE") "(사용하지 않음)" else joinedLectureContext
        val pastExamSectionTitle = if (normalizedMode == "PAST_EXAM_PRACTICE") "족보 원문 발췌" else "족보 스타일 참고"
        val excludedText = excludedQuestionTexts
            .filter { it.isNotBlank() }
            .joinToString("\n") { "- $it" }
            .ifBlank { "(없음)" }
        val difficultyGuide = when {
            normalizedMode == "FAST_REVIEW" ->
                "암기 확인용 초경량 문제로 출제합니다. 한 문제에 한 개념만 묻고, 답안은 1~3문장 또는 짧은 핵심 표현으로 끝낼 수 있어야 합니다."
            (difficultyLevel ?: 3) == 1 ->
                "대학 저학년 기초 수준으로 출제합니다. 정의, 기본 원리, 가장 대표적인 예시를 바로 떠올리면 풀 수 있어야 하며, 한 문제에 하나의 개념 또는 하나의 직접 적용만 묻습니다."
            (difficultyLevel ?: 3) == 2 ->
                "기초 개념과 기본 적용을 함께 묻는 수준으로 출제합니다. 정의를 그대로 적는 데서 끝나지 않고, 간단한 비교, 짧은 계산, 전형적인 적용 사례를 한 단계 더 요구합니다."
            (difficultyLevel ?: 3) == 3 ->
                "일반적인 대학 중간/기말고사 평균 난이도로 출제합니다. 두 개 이상의 개념을 연결하거나, 절차/이유/비교 기준을 함께 설명해야 정답이 되는 문제를 포함합니다."
            (difficultyLevel ?: 3) == 4 ->
                "풀이 과정, 응용 판단, 개념 연결이 필요한 상위권 수준으로 출제합니다. 낯선 변형 상황, 다단계 계산, 설계 판단, 예외 조건 비교처럼 중간 추론이 필요한 문항을 적극 포함합니다."
            else ->
                "대학 교육과정 범위 안에서 가장 어려운 축으로 출제합니다. 여러 개념을 동시에 엮어 스스로 가정을 세우거나, 제한 조건과 반례, trade-off, 증명 또는 복합 계산을 요구하는 문항을 포함합니다."
        }
        val styleGuide = when (normalizedMode) {
            "FAST_REVIEW" -> {
                """
                - 패스트 모의고사는 암기 확인용 초경량 문제만 만든다
                - 문제는 OX 퀴즈처럼 빠르게 풀 수 있어야 하며, 한 문제에 하나의 핵심 개념만 묻는다
                - "정의하시오", "맞으면 이유를 짧게 쓰시오", "틀리면 올바른 용어를 쓰시오", "핵심 개념을 한 줄로 서술하시오" 같은 짧은 개념 서술형 문장을 사용한다
                - 계산 과정, 긴 논증, 복잡한 구현, 장문의 비교 서술은 금지한다
                - 강의자료 전 범위를 넓게 훑되, 각 문제는 가볍고 분명해야 한다
                """.trimIndent()
            }
            "PAST_EXAM" -> {
                """
                - 강의 자료 발췌는 문제 내용의 유일한 근거로 사용하고, 족보 스타일 참고 발췌는 난이도와 문장 스타일을 맞추는 용도로만 사용할 것
                - 족보 스타일 참고 발췌에 나타난 서술 방식, 반복 포인트, 난이도 체감, 풀이 요구 깊이를 최대한 반영할 것
                - 족보 문장을 복사하거나 외부 내용을 섞지 말고, 강의자료 범위 안에서만 재구성할 것
                - 실제 대학 시험지처럼 "설명하시오", "비교하시오", "서술하시오", "구하시오", "정의하고 예를 드시오" 같은 문장형 표현을 적극 사용할 것
                """.trimIndent()
            }
            "PAST_EXAM_PRACTICE" -> {
                """
                - 강의 자료는 사용하지 말고, 업로드된 족보 원문 발췌만을 기반으로 문제를 복원할 것
                - 족보 원문 발췌에 실제 문제 문장, 보기, 계산 조건, 명령어, 코드 요구가 있으면 그 구조와 의도를 최대한 그대로 살린 연습 문제로 재구성할 것
                - 단, OCR 추출 원문에는 띄어쓰기/문자 인식 오류가 있을 수 있으므로 족보 내부 문맥을 기준으로만 보수적으로 후보정할 것
                - OCR이 불명확한 부분은 함부로 외삽하지 말고, 문맥상 확실한 범위까지만 자연스럽게 복원할 것
                - 족보 그대로 연습 모드에서는 스타일 참고가 아니라 실제 기출 복원 연습이라는 점을 반영해, 원문 문제 유형과 풀이 요구를 우선 유지할 것
                - 외부 지식으로 새 문제를 창작하기보다, 업로드된 족보의 문제 의도와 표현을 보강·정리한 실전 연습지처럼 작성할 것
                """.trimIndent()
            }
            else -> {
                """
                - 실제 대학 전공 과목의 중간고사 또는 기말고사 스타일로 출제할 것
                - 학생이 답안지 또는 실습 환경에서 실제로 풀고 채점받을 수 있는 시험문항으로 만들 것
                - 면접 질문처럼 "말해보세요"가 아니라 시험지 문장처럼 작성할 것
                """.trimIndent()
            }
        }
        val subjectGuide = """
            - 학과가 상경/경영/경제/회계/재무/통계 계열이거나 강의자료가 수식 중심이면 계산 과정과 식 전개가 드러나는 계산형 문제를 적극 포함할 것
            - 학과가 기계/전기/전자/화공/산업/물리 계열이거나 강의자료가 공식, 단위, 공학 해석 중심이면 계산형과 개념 정의형을 함께 출제할 것
            - 과목이 자료구조, 알고리즘, 운영체제, 데이터베이스, 시스템 프로그래밍, 네트워크와 같이 구현 비중이 높다면 학생이 실제 코드로 작성해야 하는 문제를 만들 것
            - CODING 문제는 "코드로 작성하시오" 또는 그에 준하는 시험지 문장으로 쓰고, 언어를 고정하지 말며, 입력/출력 또는 함수 시그니처/조건을 분명히 제시할 것
            - 리눅스관리처럼 실습형 과목은 PRACTICAL 문제로 분류하고 명령어를 실제로 작성하게 만들 것
            - 교양 과목, 인문사회 과목, 읽기·토론·이론 중심 과목은 강의자료를 충실히 반영한 서술형/비교형/비판형 문제를 만들 것
            - 모든 문제는 강의자료 범위를 벗어나지 말고, 대학생 수준을 넘는 전문 자격시험 스타일로 과도하게 비약하지 말 것
        """.trimIndent()
        val outputRules = """
            - questionStyle은 반드시 DEFINITION, CODING, CALCULATION, ESSAY, PRACTICAL 중 하나
            - questionText는 시험지에 그대로 넣을 수 있는 문장
            - canonicalAnswer는 채점 기준이 되는 정답/모범해설을 작성하되, FAST_REVIEW면 1~3문장, 그 외에는 4~10문장 수준으로 작성
            - gradingCriteria는 부분점수 기준이 드러나도록 핵심 채점 포인트를 작성하되, FAST_REVIEW면 2~4개 정도의 짧은 채점 포인트로 작성
            - referenceExample은 CODING, CALCULATION, PRACTICAL 문제에서는 절대 null이면 안 되며, 입력/출력 예시, 명령어 예시, 계산 전개 예시 중 최소 하나를 반드시 포함
            - referenceExample은 DEFINITION 또는 ESSAY 문제에서만 null 가능
            - CODING 문제의 canonicalAnswer는 실제 코드 또는 언어 중립 의사코드가 아니라, 채점 포인트와 정답 로직 설명을 담은 해설이어야 한다
            - CODING 문제의 gradingCriteria에는 기능 충족, 자료구조/알고리즘 선택, 시간복잡도 또는 핵심 로직, 예외 처리, 문법/컴파일 안정성 감점 요소가 드러나야 한다
            - CODING 문제의 referenceExample에는 입력 예시, 출력 예시, 함수 시그니처, 제약 조건 중 최소 하나를 반드시 포함해야 한다
            - maxScore는 20으로 고정
        """.trimIndent()

        return """
            ${generationSystemRole(language, "university exam question author")}
            Generate realistic written exam questions in ${language.displayLanguageName()}.

            [대학교]
            $universityName

            [학과]
            $departmentName

            [과목명]
            $courseName

            [교수명]
            ${professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"}

            [난이도]
            ${difficultyLevel ?: "족보 기준 반영"}

            [난이도 기준]
            $difficultyGuide

            [요청 문제 스타일]
            $styleText

            [출제 모드]
            $normalizedMode

            [$lectureSectionTitle]
            $lectureSectionBody

            [$pastExamSectionTitle]
            ${joinedStyleReference.ifBlank { "(없음)" }}

            [이미 생성된 문제 - 중복 금지]
            $excludedText

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "실제 시험지에 들어갈 문제 문장",
                  "questionStyle": "DEFINITION|CODING|CALCULATION|ESSAY|PRACTICAL",
                  "canonicalAnswer": "채점 기준이 되는 정답/모범해설",
                  "gradingCriteria": "부분점수 기준이 드러나는 채점 포인트",
                  "referenceExample": "예시 입력/출력/명령어/계산 예시 또는 null",
                  "maxScore": 20
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 문제 생성
            - 출제 모드가 PAST_EXAM_PRACTICE가 아니라면 모든 문제는 제공된 강의 자료 발췌에서 직접 근거를 찾을 수 있어야 함
            - 출제 모드가 PAST_EXAM_PRACTICE라면 모든 문제는 업로드된 족보 원문 발췌에서 직접 근거를 찾을 수 있어야 함
            - 출제 모드가 FAST_REVIEW라면 모든 문제는 짧고 즉답 가능한 개념 확인형이어야 하며, 한 문제당 하나의 개념만 물어야 함
            - 업로드된 자료에 없는 개념, 교수 스타일, 외부 족보 내용을 임의로 추가하지 말 것
            - 서로 다른 핵심 주제와 단원을 고르게 반영할 것
            - 강의자료 발췌가 여러 파일/여러 구간으로 주어졌다면 특정 앞부분이나 일부 자료에 편중하지 말고 전체 범위를 최대한 넓게 커버할 것
            - 문제 세트 전체 기준으로 초반 개념, 중간 핵심 개념, 후반 심화 개념이 고르게 섞이도록 설계할 것
            - 같은 소주제 주변에서 표현만 바꾼 문제를 여러 개 만들지 말고, 단원 범위를 바꿔가며 출제할 것
            - 난이도 1이면 직접 회상형/기초 적용형을 중심으로 만들고, 난이도 4~5면 개념 결합형, 변형 응용형, 다단계 추론형 문제를 분명히 늘릴 것
            - 난이도가 올라갈수록 채점 기준만 까다롭게 만들지 말고, questionText 자체가 더 긴 사고 과정과 더 넓은 범위를 요구하도록 설계할 것
            - 난이도 1~2에서는 숨은 함정이나 과도한 조건 비틀기를 넣지 말 것
            - 난이도 4~5에서는 단순 암기형 비중을 낮추고, "왜", "어떻게", "비교", "설계", "예외", "가정"을 묻는 문항 비중을 높일 것
            - 모든 문제의 questionStyle은 반드시 요청된 스타일 집합(${normalizedStyles.joinToString(", ").ifBlank { "DEFINITION" }}) 안에 있어야 함
            - 사용자가 CODING만 선택했다면 모든 문제를 CODING으로, CALCULATION만 선택했다면 모든 문제를 CALCULATION으로 작성할 것
            - 요청하지 않은 DEFINITION, CALCULATION, ESSAY, PRACTICAL, CODING 스타일을 임의로 섞지 말 것
            - 문제마다 출제 포인트가 겹치지 않게 하고, 정의형/비교형/과정 설명형/응용형/구현형/계산형을 적절히 섞을 것
            - [이미 생성된 문제 - 중복 금지] 목록과 주제, 표현, 답안 포인트가 겹치는 문제는 만들지 말 것
            - "강의자료에 따르면", "제공된 자료를 참고하여" 같은 메타 표현은 금지
            - raw snippet을 길게 복붙하지 말고 시험문장으로 재작성할 것
            - 너무 포괄적이거나 아무 과목에나 쓸 수 있는 질문은 금지
            - $departmentName 학과의 $courseName 과목이라는 점이 드러날 정도로 구체적인 개념과 표현을 사용할 것
            - 출제 모드가 PAST_EXAM_PRACTICE라면 업로드된 족보 문제의 표현, 조건, 요구사항을 보강하여 실전 연습지처럼 복원하되 OCR 오류는 족보 내부 문맥 기준으로만 보정할 것
            - CODING 문제는 반드시 학생이 소스코드를 작성해야 풀 수 있는 형태여야 하며, 문제 문장에 "코드로 작성하시오" 또는 동등한 지시를 포함할 것
            - CODING 문제는 특정 언어를 강제하지 말고, 학교별 교육과정 차이를 고려해 언어 자유로 출제할 것
            - CODING 문제는 컴파일 오류 가능성이 큰 문법 오류가 감점 요소가 되도록 채점 기준을 설계할 것
            - 코딩/실습형 문제는 예제나 명령어, 정확한 평가 포인트를 포함해 실제 채점이 가능해야 함
            - 계산형 문제는 식 설정, 변수 의미, 단위 또는 풀이 절차가 정답에 포함되어야 함
            - 정의형/서술형 문제는 핵심 개념 정의, 비교 포인트, 적용 맥락이 정답에 드러나야 함
            - 난이도 3 이상에서는 최소 일부 문항이 "개념 A만"이 아니라 "A와 B의 관계", "조건 변화에 따른 차이", "선택 근거"를 묻게 할 것
            - 난이도 4~5에서는 단순히 공식을 쓰게 하는 데서 끝나지 말고, 왜 그 식을 선택하는지 또는 어떤 조건에서 식이 달라지는지까지 묻게 할 것
            - 난이도 4~5의 CODING/PRACTICAL 문제는 기능 요구, 제약 조건, 예외 처리 또는 성능 고려 중 최소 2개 이상을 동시에 요구할 것
            - FAST_REVIEW에서는 DEFINITION 스타일만 사용하고, 답안 길이와 채점 기준을 최대한 간결하게 유지할 것
            - maxScore는 모든 문제에 대해 20으로 설정할 것
            - ${language.displayLanguageName()}로만 작성할 것
            - 반드시 JSON만 출력
            $subjectGuide
            $outputRules
            $styleGuide
        """.trimIndent()
    }

    private fun buildCourseMaterialSummaryOutlinePrompt(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        sources: List<CourseMaterialSummarySource>,
        preservedTerms: List<String>,
        language: InterviewLanguage
    ): String {
        val payload = sources.map { source ->
            mapOf(
                "fileName" to source.fileName,
                "snippets" to source.snippets
            )
        }
        return """
            ${generationSystemRole(language, "university course summary planner")}
            ${jsonLanguageInstruction(language)}

            [대학교]
            $universityName

            [학과]
            $departmentName

            [과목명]
            $courseName

            [교수명]
            ${professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"}

            [원문 용어 유지 목록]
            ${if (preservedTerms.isEmpty()) "없음" else preservedTerms.joinToString(", ")}

            [선택 강의자료 발췌]
            ${objectMapper.writeValueAsString(payload)}

            출력 JSON 스키마:
            {
              "title": "원문 용어를 살린 요약본 제목",
              "overviewFocus": "과목의 큰 흐름을 어떻게 설명할지 1~2문장 메모",
              "coreTakeawayAngles": [
                "한눈에 보기에서 반드시 다룰 핵심 관점",
                "..."
              ],
              "majorTopics": [
                {
                  "title": "대주제 제목",
                  "summaryFocus": "이 대주제의 핵심 논점 메모",
                  "subtopics": [
                    {
                      "title": "소주제 제목",
                      "summaryFocus": "이 소주제에서 반드시 설명할 개념/원리/비교 포인트 메모",
                      "mustUseTerms": ["반드시 원문으로 남길 용어", "..."]
                    }
                  ]
                }
              ]
            }

            규칙:
            - 반드시 JSON 객체만 반환
            - 발췌를 읽고 먼저 목차와 설명 흐름만 설계할 것
            - 대주제 순서는 배경 -> 핵심 메커니즘 -> 구조/구성요소 -> 동작/추론 -> 활용/의미처럼 이해가 자연스럽게 이어지도록 짤 것
            - majorTopics는 3~6개, 각 대주제 subtopics는 3~5개 작성할 것
            - title, 대주제 제목, 소주제 제목에는 발췌에 나온 원문 용어를 우선 사용할 것
            - 원문 용어 유지 목록에 있는 전문용어, 약어, 수식 표기는 번역하지 말 것
            - 같은 내용을 반복하는 소주제는 만들지 말 것
            - "설명한다", "다룬다", "중요하다" 같은 일반론 대신 실제로 써야 할 개념, 비교축, 인과관계를 메모할 것
            - ${jsonObjectOnlyRule(language)}
        """.trimIndent()
    }

    private fun buildCourseMaterialSummaryPrompt(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        sources: List<CourseMaterialSummarySource>,
        outline: CourseMaterialSummaryOutline,
        preservedTerms: List<String>,
        language: InterviewLanguage
    ): String {
        val payload = sources.map { source ->
            mapOf(
                "fileName" to source.fileName,
                "snippets" to source.snippets
            )
        }
        return """
            ${generationSystemRole(language, "university course summary writer")}
            ${jsonLanguageInstruction(language)}

            [대학교]
            $universityName

            [학과]
            $departmentName

            [과목명]
            $courseName

            [교수명]
            ${professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"}

            [원문 용어 유지 목록]
            ${if (preservedTerms.isEmpty()) "없음" else preservedTerms.joinToString(", ")}

            [작성 계획]
            ${objectMapper.writeValueAsString(outline)}

            [선택 강의자료 발췌]
            ${objectMapper.writeValueAsString(payload)}

            출력 JSON 스키마:
            {
              "title": "요약본 제목",
              "overview": "과목 전체 흐름을 설명하는 2~3문장 요약",
              "coreTakeaways": [
                "강의 전체를 빠르게 이해할 수 있는 핵심 정리 문장",
                "..."
              ],
              "majorTopics": [
                {
                  "title": "대주제 제목",
                  "summary": "이 대주제의 핵심 맥락과 학술적 의의를 설명하는 1~2문장",
                  "subtopics": [
                    {
                      "title": "소주제 제목",
                      "summary": "소주제의 핵심 개념과 맥락을 설명하는 1~2문장",
                      "keyPoints": [
                        "핵심 개념, 정의, 원리, 절차, 공식, 비교 포인트 중 하나를 구체적으로 설명하는 짧고 선명한 문장",
                        "..."
                      ],
                      "supplementaryNotes": [
                        "필요할 때만 추가하는 보충 설명, 직관, 간단한 흐름 설명 또는 짧은 비유",
                        "..."
                      ]
                    }
                  ]
                }
              ]
            }

            규칙:
            - 선택된 강의자료 발췌만 근거로 사용할 것
            - 자료에 없는 내용은 사실처럼 단정해서 보태지 말 것
            - 작성 계획의 대주제/소주제 흐름을 기본 골격으로 따를 것
            - title, overview, coreTakeaways, majorTopics, subtopics 어디에서도 원문 용어 유지 목록의 표기를 번역하거나 한글식으로 바꾸지 말 것
            - 기술 용어는 영어 원문을 기본 표기로 사용할 것. 한국어만 단독으로 쓰지 말 것
            - 원문 용어를 설명할 때는 "Transformer Encoder(인코더 블록)"처럼 영어 원문을 먼저 쓰고, 필요한 경우에만 괄호 설명을 덧붙일 것
            - 영문 전문용어를 통째로 한글화한 제목("트랜스포머 인코더", "어텐션 메커니즘", "쿼리/키/밸류")은 금지하고, 반드시 영어 원문 표기를 포함할 것
            - overview는 과목 전체의 큰 흐름과 개념 간 연결 관계를 짧고 선명하게 정리할 것
            - coreTakeaways는 반드시 5~7개 작성할 것
            - coreTakeaways는 시험 직전 훑어볼 수 있는 밀도 높은 문장으로 작성할 것
            - majorTopics는 반드시 4~6개 작성할 것
            - 각 majorTopics.summary는 짧은 감상문이 아니라, 그 대주제에서 다루는 학술적 범위와 핵심 논점을 1~2문장으로 압축해서 설명할 것
            - 각 대주제마다 subtopics를 반드시 3~5개 작성할 것
            - 각 소주제에는 summary를 반드시 작성할 것
            - 각 소주제 summary는 그 소주제가 어떤 개념을 설명하고, 상위 대주제 안에서 어떤 역할을 하는지 드러내는 1~2문장으로 작성할 것
            - 각 소주제의 keyPoints는 반드시 4~6개 작성할 것
            - keyPoints는 짧은 키워드가 아니라 완결된 설명 문장으로 작성하되, 군더더기 없이 바로 이해되게 쓸 것
            - keyPoints에는 정의, 원리, 시간복잡도, 점화식, 절차, 비교 기준, 장단점, 예외 조건처럼 시험 답안에 직접 쓸 수 있는 학술 정보를 우선 포함할 것
            - 가능하면 한 소주제 안에서 "정의 -> 작동 원리 -> 예시/비교 -> 주의할 점" 순서가 드러나게 구성할 것
            - supplementaryNotes는 필요한 경우에만 0~2개 작성할 것
            - 추상적이거나 과정 설명이 필요한 소주제에는 supplementaryNotes를 최소 1개 넣어도 좋다
            - supplementaryNotes에는 이해를 돕는 짧은 추가설명, 흐름도형 설명("A -> B -> C"), 비교표현, 직관적 비유를 넣을 수 있다
            - supplementaryNotes는 자료에 없는 외부 사실을 꾸며내지 말고, 자료의 맥락을 더 쉽게 풀어 쓰는 수준에서만 사용한다
            - 학습 조언, 태도, 체크리스트, 시험 요령 같은 메타 조언은 쓰지 말 것
            - "중요하다", "다룬다", "설명한다" 같은 메타 문장은 최소화하고, 개념 자체와 인과관계를 직접 서술할 것
            - overview와 summary는 "무엇을 설명한다"보다 "왜 이 개념이 등장했고 다음 개념과 어떻게 이어지는지"를 드러낼 것
            - keyPoints와 supplementaryNotes에서 간단한 ASCII 흐름도, 비교 표현, 단계 표현을 사용할 수 있다
            - 발췌에 등장하는 용어를 최대한 그대로 살려 source-specific하게 서술할 것
            - 입력 정보가 충분하면 너무 짧게 끝내지 말고, 대주제/소주제/핵심 포인트를 충분히 채워 구조화 노트처럼 작성할 것
            - 서로 다른 자료에서 같은 개념이 반복되면 하나의 대주제로 묶고, 세부 차이는 소주제/핵심내용에서 구분할 것
            - ${jsonObjectOnlyRule(language)}
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

    private fun parseGeneratedCourseExamQuestions(raw: String): List<GeneratedCourseExamQuestion> {
        val node = objectMapper.readTree(raw)
        return node["questions"]
            ?.takeIf { it.isArray }
            ?.mapIndexedNotNull { index, item ->
                val questionText = item.text("questionText").ifBlank { return@mapIndexedNotNull null }
                val rawMaxScore = item["maxScore"]?.takeIf { it.isNumber }?.asInt()
                val sanitizedMaxScore = sanitizeCourseExamMaxScore(rawMaxScore)
                GeneratedCourseExamQuestion(
                    questionNo = index + 1,
                    questionText = questionText,
                    questionStyle = item.text("questionStyle").ifBlank { "DEFINITION" },
                    canonicalAnswer = item.text("canonicalAnswer").ifBlank { "" },
                    gradingCriteria = item.text("gradingCriteria").ifBlank { "" },
                    referenceExample = item.text("referenceExample").ifBlank { null },
                    maxScore = sanitizedMaxScore
                )
            }
            .orEmpty()
    }

    private fun parseGeneratedCourseMaterialSummary(raw: String): GeneratedCourseMaterialSummary {
        val node = objectMapper.readTree(raw)
        val title = node.text("title").trim().ifBlank { "강의자료 요약본" }
        val overview = node.text("overview").trim().ifBlank {
            throw IllegalStateException("요약 overview가 비어 있습니다.")
        }
        val coreTakeaways = node["coreTakeaways"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item -> item.asText().trim().takeIf(String::isNotBlank) }
            .orEmpty()
        if (coreTakeaways.isEmpty()) {
            throw IllegalStateException("핵심 요점 정리가 비어 있습니다.")
        }
        val majorTopics = node["majorTopics"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val topicTitle = item.text("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val summary = item.text("summary").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val subtopics = item["subtopics"]
                    ?.takeIf { it.isArray }
                    ?.mapNotNull subtopicMap@{ subtopic ->
                        val subtopicTitle = subtopic.text("title").trim().takeIf(String::isNotBlank) ?: return@subtopicMap null
                        val subtopicSummary = subtopic.text("summary").trim().takeIf(String::isNotBlank) ?: return@subtopicMap null
                        val keyPoints = subtopic["keyPoints"]
                            ?.takeIf { it.isArray }
                            ?.mapNotNull { point -> point.asText().trim().takeIf(String::isNotBlank) }
                            .orEmpty()
                        if (keyPoints.isEmpty()) return@subtopicMap null
                        val supplementaryNotes = subtopic["supplementaryNotes"]
                            ?.takeIf { it.isArray }
                            ?.mapNotNull { note -> note.asText().trim().takeIf(String::isNotBlank) }
                            .orEmpty()
                        GeneratedCourseMaterialSummarySubtopic(
                            title = subtopicTitle,
                            summary = subtopicSummary,
                            keyPoints = keyPoints,
                            supplementaryNotes = supplementaryNotes
                        )
                    }
                    .orEmpty()
                if (subtopics.isEmpty()) return@mapNotNull null
                GeneratedCourseMaterialSummaryTopic(
                    title = topicTitle,
                    summary = summary,
                    subtopics = subtopics
                )
            }
            .orEmpty()
        if (majorTopics.isEmpty()) {
            throw IllegalStateException("요약본 구조가 충분하지 않습니다.")
        }
        return GeneratedCourseMaterialSummary(
            title = title,
            overview = overview,
            coreTakeaways = coreTakeaways,
            majorTopics = majorTopics
        )
    }

    private fun parseCourseMaterialSummaryOutline(raw: String): CourseMaterialSummaryOutline {
        val node = objectMapper.readTree(raw)
        val title = node.text("title").trim().ifBlank { "강의자료 요약본" }
        val overviewFocus = node.text("overviewFocus").trim().ifBlank {
            throw IllegalStateException("요약 작성 계획 overviewFocus가 비어 있습니다.")
        }
        val coreTakeawayAngles = node["coreTakeawayAngles"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item -> item.asText().trim().takeIf(String::isNotBlank) }
            .orEmpty()
        if (coreTakeawayAngles.isEmpty()) {
            throw IllegalStateException("요약 작성 계획 coreTakeawayAngles가 비어 있습니다.")
        }
        val majorTopics = node["majorTopics"]
            ?.takeIf { it.isArray }
            ?.mapNotNull topicMap@{ item ->
                val topicTitle = item.text("title").trim().takeIf(String::isNotBlank) ?: return@topicMap null
                val summaryFocus = item.text("summaryFocus").trim().takeIf(String::isNotBlank) ?: return@topicMap null
                val subtopics = item["subtopics"]
                    ?.takeIf { it.isArray }
                    ?.mapNotNull subtopicMap@{ subtopic ->
                        val subtopicTitle = subtopic.text("title").trim().takeIf(String::isNotBlank) ?: return@subtopicMap null
                        val subtopicSummaryFocus = subtopic.text("summaryFocus").trim().takeIf(String::isNotBlank) ?: return@subtopicMap null
                        val mustUseTerms = subtopic["mustUseTerms"]
                            ?.takeIf { it.isArray }
                            ?.mapNotNull { term -> term.asText().trim().takeIf(String::isNotBlank) }
                            .orEmpty()
                        CourseMaterialSummaryOutlineSubtopic(
                            title = subtopicTitle,
                            summaryFocus = subtopicSummaryFocus,
                            mustUseTerms = mustUseTerms
                        )
                    }
                    .orEmpty()
                if (subtopics.isEmpty()) return@topicMap null
                CourseMaterialSummaryOutlineTopic(
                    title = topicTitle,
                    summaryFocus = summaryFocus,
                    subtopics = subtopics
                )
            }
            .orEmpty()
        if (majorTopics.isEmpty()) {
            throw IllegalStateException("요약 작성 계획 majorTopics가 비어 있습니다.")
        }
        return CourseMaterialSummaryOutline(
            title = title,
            overviewFocus = overviewFocus,
            coreTakeawayAngles = coreTakeawayAngles,
            majorTopics = majorTopics
        )
    }

    private fun parseRefinedCourseTranscript(raw: String): String {
        val node = objectMapper.readTree(raw)
        val refinedTranscript = node.text("refinedTranscript").trim()
        if (refinedTranscript.isBlank()) {
            throw IllegalStateException("후보정 자막 결과가 비어 있습니다.")
        }
        return refinedTranscript
    }

    private fun extractPreservedTerms(sources: List<CourseMaterialSummarySource>): List<String> {
        val weightedTerms = linkedMapOf<String, Int>()
        sources.forEachIndexed { sourceIndex, source ->
            val sourceWeight = 10 - sourceIndex
            accumulatePreservedTerms(source.fileName, weightedTerms, sourceWeight + 4)
            source.snippets.forEachIndexed { snippetIndex, snippet ->
                accumulatePreservedTerms(snippet, weightedTerms, maxOf(1, sourceWeight - (snippetIndex / 2)))
            }
        }
        return weightedTerms.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
            )
            .map { it.key }
            .take(18)
    }

    private fun accumulatePreservedTerms(
        text: String,
        weightedTerms: MutableMap<String, Int>,
        weight: Int
    ) {
        preservedTermPhraseRegex.findAll(text).forEach { match ->
            val normalized = normalizePreservedTerm(match.value) ?: return@forEach
            weightedTerms[normalized] = (weightedTerms[normalized] ?: 0) + weight + normalized.length
        }
    }

    private fun normalizePreservedTerm(raw: String): String? {
        val normalized = raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', ':', ';', '(', ')', '[', ']', '{', '}', '"', '\'')
        if (normalized.length < 2) return null
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) {
            val token = tokens.first()
            if (token.lowercase() in preservedSingleWordStopwords) return null
            if (!(token.any { it.isUpperCase() } || token.any { it.isDigit() } || token.contains('-') || token.length >= 4)) {
                return null
            }
        } else {
            val nonStopwordExists = tokens.any { it.lowercase() !in preservedSingleWordStopwords }
            if (!nonStopwordExists) return null
        }
        return normalized
    }

    private fun validatePreservedTerminology(
        summary: GeneratedCourseMaterialSummary,
        preservedTerms: List<String>
    ) {
        val priorityTerms = preservedTerms
            .filter { term ->
                term.split(' ').size >= 2 || term.any { it.isUpperCase() } || term.any { it.isDigit() } || term.contains('-')
            }
            .take(8)
        if (priorityTerms.isEmpty()) return

        val renderedSummary = buildString {
            append(summary.title).append('\n')
            append(summary.overview).append('\n')
            summary.coreTakeaways.forEach { append(it).append('\n') }
            summary.majorTopics.forEach { topic ->
                append(topic.title).append('\n')
                append(topic.summary).append('\n')
                topic.subtopics.forEach { subtopic ->
                    append(subtopic.title).append('\n')
                    append(subtopic.summary).append('\n')
                    subtopic.keyPoints.forEach { append(it).append('\n') }
                    subtopic.supplementaryNotes.forEach { append(it).append('\n') }
                }
            }
        }
        val usedCount = priorityTerms.count { term ->
            Regex("""(?i)\b${Regex.escape(term)}\b""").containsMatchIn(renderedSummary)
        }
        val minimumRequired = when {
            priorityTerms.size >= 6 -> 3
            priorityTerms.size >= 4 -> 2
            else -> 1
        }
        if (usedCount < minimumRequired) {
            throw IllegalStateException("원문 전문용어가 충분히 보존되지 않았습니다.")
        }
    }

    private fun validateCourseMaterialSummaryDensity(summary: GeneratedCourseMaterialSummary) {
        if (summary.coreTakeaways.size < 4) {
            throw IllegalStateException("한눈에 보기 분량이 부족합니다.")
        }
        if (summary.majorTopics.size < 3) {
            throw IllegalStateException("대주제 수가 부족합니다.")
        }
        val totalSubtopics = summary.majorTopics.sumOf { it.subtopics.size }
        if (totalSubtopics < summary.majorTopics.size * 2) {
            throw IllegalStateException("소주제 분량이 부족합니다.")
        }
        val totalKeyPoints = summary.majorTopics.sumOf { topic ->
            topic.subtopics.sumOf { subtopic -> subtopic.keyPoints.size }
        }
        if (totalKeyPoints < totalSubtopics * 3) {
            throw IllegalStateException("핵심 포인트 분량이 부족합니다.")
        }
    }

    private fun GeneratedCourseMaterialSummary.normalizeTechnicalTerminology(
        preservedTerms: List<String>
    ): GeneratedCourseMaterialSummary {
        val alwaysActiveGlossary = setOf(
            "Architecture",
            "Mechanism",
            "Transformer",
            "Attention",
            "Seq2Seq",
            "Encoder",
            "Decoder",
            "Query",
            "Key",
            "Value",
            "Positional Encoding",
            "Multi-Head Attention",
            "Residual Connection",
            "Layer Normalization",
            "Beam Search"
        )
        val activeGlossary = technicalTermGlossary.filter { entry ->
            preservedTerms.any { preserved ->
                preserved.equals(entry.canonical, ignoreCase = true) ||
                    preserved.contains(entry.canonical, ignoreCase = true) ||
                    entry.canonical.contains(preserved, ignoreCase = true)
            } || entry.canonical in alwaysActiveGlossary
        }
        if (activeGlossary.isEmpty()) return this

        fun rewrite(text: String): String {
            var rewritten = text
            activeGlossary.forEach { entry ->
                entry.aliases.forEach { alias ->
                    rewritten = rewritten.replace(buildTechnicalAliasRegex(alias), entry.canonical)
                }
            }
            return rewritten
                .replace(Regex("""\b(Architecture|Mechanism|Transformer|Attention|Encoder|Decoder|Query|Key|Value|Seq2Seq)\s+\1\b"""), "$1")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
        }

        return copy(
            title = rewrite(title),
            overview = rewrite(overview),
            coreTakeaways = coreTakeaways.map(::rewrite),
            majorTopics = majorTopics.map { topic ->
                topic.copy(
                    title = rewrite(topic.title),
                    summary = rewrite(topic.summary),
                    subtopics = topic.subtopics.map { subtopic ->
                        subtopic.copy(
                            title = rewrite(subtopic.title),
                            summary = rewrite(subtopic.summary),
                            keyPoints = subtopic.keyPoints.map(::rewrite),
                            supplementaryNotes = subtopic.supplementaryNotes.map(::rewrite)
                        )
                    }
                )
            }
        )
    }

    private fun buildTechnicalAliasRegex(alias: String): Regex {
        return Regex("""(?<![A-Za-z0-9가-힣])${Regex.escape(alias)}(?![A-Za-z0-9가-힣])""")
    }

    private fun buildPastExamPracticeRefinementPrompt(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        extractedQuestions: List<PastExamPracticeQuestionCandidate>,
        language: InterviewLanguage
    ): String {
        val professorLine = professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"
        val extractedSection = extractedQuestions.joinToString("\n\n") { item ->
            """
            [원문 문제 ${item.questionNo}]
            자료명: ${item.sourceFileName}
            추출 방식: ${item.extractionMethod ?: "UNKNOWN"}
            문제 원문:
            ${item.questionText}
            """.trimIndent()
        }

        return """
            당신은 대학 족보 원문을 정리하는 편집자이자 채점 기준 작성자다.
            목적은 새 문제를 만드는 것이 아니라, 추출된 족보 원문 문제를 그대로 연습용으로 정리하는 것이다.

            [학교/학과/과목]
            학교: $universityName
            학과: $departmentName
            과목: $courseName
            교수명: $professorLine

            [원문 문제 목록]
            $extractedSection

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "족보 원문을 그대로 유지한 문제 문장",
                  "questionStyle": "DEFINITION | CODING | CALCULATION | ESSAY | PRACTICAL",
                  "canonicalAnswer": "정답 또는 모범해설",
                  "gradingCriteria": "부분점수 기준이 드러나는 채점 기준",
                  "referenceExample": "코딩/계산/실습형일 때만 필요하면 제공, 아니면 null",
                  "maxScore": 20
                }
              ]
            }

            절대 규칙:
            - 새 문제를 만들지 말 것
            - 서로 다른 문제를 합치거나 나누지 말 것
            - 숫자, 조건, 행렬 크기, 입력값, 배열, 그래프 정보, 요구사항을 임의로 바꾸지 말 것
            - 강의자료나 외부 지식을 참조해 문제 문장을 재구성하지 말 것
            - questionText는 원문을 최대한 그대로 유지할 것
            - questionText는 띄어쓰기, 줄바꿈, 문장부호, 명백한 OCR 깨짐, 말이 되지 않는 문자만 최소한으로 보정할 수 있다
            - 원문이 이미 자연스러우면 questionText를 거의 그대로 둘 것
            - questionStyle은 원문 문제의 실제 유형을 분류할 것
            - canonicalAnswer와 gradingCriteria는 문제를 푸는 데 필요한 정답/채점기준을 작성하되, questionText 자체는 바꾸지 말 것
            - CODING 문제는 언어를 고정하지 말고 로직/입출력/핵심 구현 포인트 중심으로 채점 기준을 작성할 것
            - CALCULATION 문제는 계산 절차와 중간 핵심 결과를 포함할 것
            - referenceExample은 CODING, CALCULATION, PRACTICAL에서만 필요하면 제공할 것
            - 모든 questionText, canonicalAnswer, gradingCriteria, referenceExample은 ${language.displayLanguageName()}로 작성할 것
            - 반드시 입력 문제 수와 동일한 개수, 동일한 순서로 JSON만 출력할 것
        """.trimIndent()
    }

    private fun buildPastExamPracticeRecoveryPrompt(
        universityName: String,
        departmentName: String,
        courseName: String,
        professorName: String?,
        sourceFileName: String,
        extractionMethod: String?,
        rawText: String,
        expectedQuestionCount: Int,
        language: InterviewLanguage
    ): String {
        val professorLine = professorName?.trim()?.takeIf { it.isNotBlank() } ?: "미상"
        return """
            당신은 OCR로 추출된 대학 족보 원문을 문제 단위로 복원하는 편집자다.
            목적은 새 문제를 만드는 것이 아니라, 깨진 OCR 텍스트에서 원래 존재하던 문제 경계만 복원하는 것이다.

            [학교/학과/과목]
            학교: $universityName
            학과: $departmentName
            과목: $courseName
            교수명: $professorLine

            [원문 정보]
            자료명: $sourceFileName
            추출 방식: ${extractionMethod ?: "UNKNOWN"}
            기대 문제 수: 최대 ${expectedQuestionCount}개

            [OCR 원문]
            $rawText

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "원문 문제 1개"
                }
              ]
            }

            절대 규칙:
            - 새 문제를 만들지 말 것
            - 원문에 없는 숫자, 조건, 행렬 크기, 그래프 정보, 변수, 배열, 입력값을 추가하지 말 것
            - 여러 문제를 하나로 합치지 말 것
            - 하나의 문제를 임의로 둘로 나누지 말 것
            - questionText는 원문 표현을 최대한 유지할 것
            - 띄어쓰기, 줄바꿈, 문장부호, 명백한 OCR 깨짐, 말이 되지 않는 문자만 최소한으로 보정할 수 있다
            - 강의자료나 외부 지식을 사용하지 말 것
            - 원문에서 확인 가능한 문제만 반환할 것
            - 문제 수가 확실하지 않으면 무리해서 개수를 맞추지 말고, 식별 가능한 문제만 반환할 것
            - 반환 개수는 1개 이상 ${expectedQuestionCount}개 이하로 제한할 것
            - 모든 questionText는 ${language.displayLanguageName()}로 작성할 것
            - 반드시 JSON만 출력할 것
        """.trimIndent()
    }

    private fun parseRecoveredPastExamPracticeQuestions(json: String): List<String> {
        val root = objectMapper.readTree(json)
        return root.path("questions")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { node ->
                node.path("questionText")
                    .asText()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length >= 12 }
            }
            .orEmpty()
    }

    private fun validateGeneratedDocumentQuestions(
        generated: List<GeneratedDocumentQuestion>,
        fileTypeLabel: String,
        existingAccepted: List<GeneratedDocumentQuestion> = emptyList()
    ): DocumentQuestionValidationResult {
        val seen = linkedSetOf<String>()
        val accepted = mutableListOf<GeneratedDocumentQuestion>()
        val rejectedReasons = mutableListOf<String>()
        generated.forEach { item ->
            val normalizedEvidenceKind = normalizeEvidenceKind(item.evidenceKind)
            val normalizedQuestionType = normalizeDocumentQuestionType(
                questionType = item.questionType,
                fileTypeLabel = fileTypeLabel,
                evidenceKind = normalizedEvidenceKind,
                questionText = item.questionText
            )
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            val usabilityRejectReason = documentQuestionUsabilityRejectReason(
                normalizedQuestion,
                normalizedQuestionType,
                normalizedEvidenceKind
            )
            if (usabilityRejectReason != null) {
                rejectedReasons += usabilityRejectReason
                return@forEach
            }

            val fingerprint = normalizedQuestion
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            if (!seen.add(fingerprint)) {
                rejectedReasons += "duplicate_question"
                return@forEach
            }

            if (!isAllowedDocumentQuestionType(normalizedQuestionType, fileTypeLabel, normalizedEvidenceKind)) {
                rejectedReasons += "question_type_mismatch"
                return@forEach
            }
            if (!isCompatibleQuestionForEvidenceKind(normalizedQuestion, normalizedQuestionType, normalizedEvidenceKind)) {
                rejectedReasons += "question_evidence_kind_mismatch"
                return@forEach
            }

            val normalizedAnswer = item.referenceAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?: run {
                    rejectedReasons += "missing_reference_answer"
                    return@forEach
                }
            if (normalizedAnswer.isBlank()) {
                rejectedReasons += "blank_reference_answer"
                return@forEach
            }
            if (isGuideLikeModelAnswer(normalizedAnswer)) {
                rejectedReasons += "guide_like_reference_answer"
                return@forEach
            }
            if (!isDocumentAnswerLinkedToQuestion(normalizedAnswer, normalizedQuestion, normalizedQuestionType)) {
                rejectedReasons += "reference_answer_not_linked"
                return@forEach
            }
            if (!isCompatibleReferenceAnswer(normalizedAnswer, normalizedQuestionType, normalizedEvidenceKind)) {
                rejectedReasons += "reference_answer_evidence_kind_mismatch"
                return@forEach
            }

            val normalizedEvidence = item.evidence
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 8 }
                .distinct()
                .take(4)
            if (normalizedEvidence.isEmpty()) {
                rejectedReasons += "empty_evidence"
                return@forEach
            }
            if ((existingAccepted + accepted).any {
                    isSemanticallyDuplicateDocumentQuestion(
                        existingQuestion = it.questionText,
                        candidateQuestion = normalizedQuestion,
                        existingEvidence = it.evidence,
                        candidateEvidence = normalizedEvidence
                    )
                }) {
                rejectedReasons += "duplicate_question_semantic"
                return@forEach
            }

            accepted += item.copy(
                questionText = normalizedQuestion,
                questionType = normalizedQuestionType,
                evidenceKind = normalizedEvidenceKind,
                referenceAnswer = normalizedAnswer,
                evidence = normalizedEvidence
            )
        }
        return DocumentQuestionValidationResult(
            accepted = accepted,
            rejectedReasons = rejectedReasons
        )
    }

    private fun isSameEvidenceSource(existing: List<String>, candidate: List<String>): Boolean {
        val existingPrimary = existing.firstOrNull()?.trim().orEmpty()
        val candidatePrimary = candidate.firstOrNull()?.trim().orEmpty()
        if (existingPrimary.isBlank() || candidatePrimary.isBlank()) return false

        val existingTokens = evidenceTokens(existingPrimary)
        val candidateTokens = evidenceTokens(candidatePrimary)
        if (existingTokens.isEmpty() || candidateTokens.isEmpty()) return false

        val overlap = flexibleTokenOverlap(existingTokens, candidateTokens)
        val minTokenSize = minOf(existingTokens.size, candidateTokens.size)
        if (overlap >= 4 && overlap * 100 >= minTokenSize * 65) return true

        val existingCompact = existingPrimary.lowercase().replace(Regex("[^0-9a-zA-Z가-힣]+"), "")
        val candidateCompact = candidatePrimary.lowercase().replace(Regex("[^0-9a-zA-Z가-힣]+"), "")
        val prefixLength = commonPrefixLength(existingCompact, candidateCompact)
        return prefixLength >= 12 && prefixLength * 100 >= minOf(existingCompact.length, candidateCompact.length) * 30
    }

    private fun evidenceTokens(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun isSemanticallyDuplicateDocumentQuestion(
        existingQuestion: String,
        candidateQuestion: String,
        existingEvidence: List<String>,
        candidateEvidence: List<String>
    ): Boolean {
        val existingTopicTokens = documentQuestionTopicTokens(existingQuestion)
        val candidateTopicTokens = documentQuestionTopicTokens(candidateQuestion)
        if (existingTopicTokens.isEmpty() || candidateTopicTokens.isEmpty()) return false

        val overlap = flexibleTokenOverlap(existingTopicTokens, candidateTopicTokens)
        val minTopicSize = minOf(existingTopicTokens.size, candidateTopicTokens.size)
        val strongTopicOverlap = overlap >= 4 && overlap * 100 >= minTopicSize * 65
        val sharedLeadNarrative = hasSharedQuestionLead(existingQuestion, candidateQuestion)
        val sameTopic = isSameEvidenceSource(existingEvidence, candidateEvidence) || strongTopicOverlap || sharedLeadNarrative
        if (!sameTopic) return false

        val existingIntents = questionIntentSignals(existingQuestion)
        val candidateIntents = questionIntentSignals(candidateQuestion)
        if (existingIntents.isEmpty() || candidateIntents.isEmpty()) return false

        return existingIntents.intersect(candidateIntents).isNotEmpty()
    }

    private fun documentQuestionTopicTokens(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { duplicateQuestionStopTokens.contains(it) }
            .toSet()
    }

    private fun flexibleTokenOverlap(first: Set<String>, second: Set<String>): Int {
        return first.count { left ->
            second.any { right ->
                left == right || (left.length >= 3 && right.length >= 3 && (left.startsWith(right) || right.startsWith(left)))
            }
        }
    }

    private fun hasSharedQuestionLead(first: String, second: String): Boolean {
        val firstCompact = first.lowercase().replace(Regex("[^0-9a-zA-Z가-힣]+"), "")
        val secondCompact = second.lowercase().replace(Regex("[^0-9a-zA-Z가-힣]+"), "")
        if (firstCompact.length < 18 || secondCompact.length < 18) return false

        val prefixLength = commonPrefixLength(firstCompact, secondCompact)
        val minLength = minOf(firstCompact.length, secondCompact.length)
        return prefixLength >= 18 && prefixLength * 100 >= minLength * 35
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        val max = minOf(first.length, second.length)
        var index = 0
        while (index < max && first[index] == second[index]) {
            index += 1
        }
        return index
    }

    private fun questionIntentSignals(text: String): Set<String> {
        val lowered = text.lowercase()
        val intents = linkedSetOf<String>()
        if (roleIntentHints.any { lowered.contains(it) }) intents += "ROLE"
        if (reasonIntentHints.any { lowered.contains(it) }) intents += "REASON"
        if (focusIntentHints.any { lowered.contains(it) }) intents += "FOCUS"
        if (resultIntentHints.any { lowered.contains(it) }) intents += "RESULT"
        if (challengeIntentHints.any { lowered.contains(it) }) intents += "CHALLENGE"
        if (collaborationIntentHints.any { lowered.contains(it) }) intents += "COLLAB"
        return intents
    }

    private fun summarizeRejectedReasons(reasons: List<String>): String {
        return reasons.groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (reason, count) -> "$reason=$count" }
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

    private fun documentQuestionUsabilityRejectReason(
        questionText: String,
        questionType: String,
        evidenceKind: String
    ): String? {
        if (questionText.isBlank()) return "question_blank"
        val introQuestion = questionType.startsWith("INTRODUCE_")
        if (questionText.length < if (introQuestion) 12 else 16) return "question_too_short"
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
        if (banned.any { lowered.contains(it) }) return "question_banned_genericity"
        val tokens = questionText
            .split(Regex("[^0-9a-zA-Z가-힣]+"))
            .filter { it.length >= 2 }
        if (tokens.size < if (introQuestion) 3 else 4) return "question_too_few_tokens"
        val domainHints = when {
            introQuestion || evidenceKind in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE") ->
                commonDomainHints + introduceHints
            questionType.startsWith("RESUME_") ->
                commonDomainHints + introduceHints + resumeHints
            else -> commonDomainHints
        }
        if (!questionType.startsWith("RESUME_") && domainHints.none { lowered.contains(it) }) return "question_missing_domain_hint"
        if (!hasInterviewQuestionEnding(questionText)) return "question_missing_interview_ending"
        return null
    }

    private fun hasInterviewQuestionEnding(questionText: String): Boolean {
        val trimmed = questionText.trim()
        if (trimmed.endsWith("?")) return true

        val normalized = trimmed.removeSuffix(".").removeSuffix("!").trim()
        if (interviewQuestionEndings.any { normalized.endsWith(it) }) return true

        val lowered = normalized.lowercase()
        return listOf("무엇", "어떤", "왜", "어떻게", "이유", "역할", "기여", "성과", "강점", "경험", "what", "why", "how", "role", "reason")
            .any { lowered.contains(it) }
    }

    private fun documentQuestionTypeRules(fileTypeKey: String): List<String> {
        return when (fileTypeKey) {
            "RESUME" -> listOf(
                "- RESUME 문서는 questionType으로 RESUME_EXPERIENCE, RESUME_RESULT, RESUME_MOTIVATION, RESUME_VALUE 중 하나만 사용",
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

    private fun normalizeDocumentQuestionType(
        questionType: String,
        fileTypeLabel: String,
        evidenceKind: String,
        questionText: String
    ): String {
        val normalized = questionType.trim().uppercase()
        val fileTypeKey = normalizeDocumentFileType(fileTypeLabel)
        if (fileTypeKey != "RESUME") return normalized

        val lowered = questionText.lowercase()
        val resultSignals = listOf("성과", "결과", "효과", "개선", "기준", "이유", "선택", "판단", "why", "reason", "result", "impact", "decision")
        return when {
            evidenceKind == "MOTIVATION_OR_ASPIRATION" -> "RESUME_MOTIVATION"
            evidenceKind == "VALUE_OR_ATTITUDE" -> "RESUME_VALUE"
            normalized.isBlank() -> toDocumentQuestionType(fileTypeLabel)
            normalized.startsWith("RESUME_") -> normalized
            normalized == "INTRODUCE_MOTIVATION" || normalized == "INTRODUCE_FUTURE_PLAN" -> "RESUME_MOTIVATION"
            normalized == "INTRODUCE_VALUE" -> "RESUME_VALUE"
            normalized.startsWith("PORTFOLIO_") && resultSignals.any { lowered.contains(it) } -> "RESUME_RESULT"
            normalized.startsWith("PORTFOLIO_") -> "RESUME_EXPERIENCE"
            resultSignals.any { lowered.contains(it) } -> "RESUME_RESULT"
            else -> "RESUME_EXPERIENCE"
        }
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
            "RESUME" -> when (evidenceKind) {
                "MOTIVATION_OR_ASPIRATION" -> questionType in setOf("RESUME_MOTIVATION", "RESUME_VALUE")
                "VALUE_OR_ATTITUDE" -> questionType in setOf("RESUME_VALUE", "RESUME_MOTIVATION")
                else -> questionType in setOf("RESUME_EXPERIENCE", "RESUME_RESULT")
            }
            "PORTFOLIO" -> questionType in setOf("PORTFOLIO_PROJECT", "PORTFOLIO_RESULT", "PORTFOLIO_DECISION")
            else -> true
        }
    }

    private fun isCompatibleQuestionForEvidenceKind(questionText: String, questionType: String, evidenceKind: String): Boolean {
        if (evidenceKind !in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE")) return true
        if (questionType in setOf("RESUME_MOTIVATION", "RESUME_VALUE", "INTRODUCE_MOTIVATION", "INTRODUCE_VALUE", "INTRODUCE_FUTURE_PLAN")) {
            return !looksLikePastExecutionAssumption(questionText)
        }
        if (questionType.endsWith("_EXPERIENCE") || questionType.endsWith("_PROJECT") || questionType.endsWith("_RESULT") || questionType.endsWith("_DECISION")) {
            return false
        }
        return !looksLikePastExecutionAssumption(questionText)
    }

    private fun isCompatibleReferenceAnswer(referenceAnswer: String, questionType: String, evidenceKind: String): Boolean {
        return if (evidenceKind in setOf("MOTIVATION_OR_ASPIRATION", "VALUE_OR_ATTITUDE")) {
            !looksLikePastExecutionAssumption(referenceAnswer) &&
                !questionType.endsWith("_EXPERIENCE") &&
                !questionType.endsWith("_PROJECT") &&
                !questionType.endsWith("_DECISION") &&
                !questionType.endsWith("_RESULT")
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
        return normalized !in setOf(
            "INTRODUCE_MOTIVATION",
            "INTRODUCE_VALUE",
            "INTRODUCE_FUTURE_PLAN",
            "RESUME_MOTIVATION",
            "RESUME_VALUE"
        )
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
        if (questionType in setOf("INTRODUCE_MOTIVATION", "INTRODUCE_VALUE", "INTRODUCE_FUTURE_PLAN", "RESUME_MOTIVATION", "RESUME_VALUE")) {
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

    private fun isUsableCourseExamQuestion(courseName: String, questionText: String): Boolean {
        val normalized = questionText.trim()
        if (normalized.length < 14) return false
        if (normalized.length > 220) return false

        val lowered = normalized.lowercase()
        if (listOf("말해 보세요", "말해보세요", "설명해 주세요", "설명해주세요", "어떻게 생각", "의견을 말씀").any { lowered.contains(it) }) {
            return false
        }

        val examSignals = listOf(
            "설명하시오",
            "서술하시오",
            "비교하시오",
            "기술하시오",
            "정리하시오",
            "구하시오",
            "논하시오",
            "작성하시오",
            "구현하시오",
            "코드로 작성하시오",
            "프로그램을 작성하시오",
            "함수를 작성하시오",
            "메서드를 작성하시오",
            "명령어를 작성하시오"
        )
        val codingSignals = listOf(
            "코드",
            "구현",
            "함수",
            "메서드",
            "클래스",
            "프로그램",
            "입력",
            "출력",
            "의사코드",
            "알고리즘"
        )
        val hasExamSignal = examSignals.any { normalized.contains(it) } ||
            (codingSignals.any { lowered.contains(it) } && listOf("작성", "구현", "하시오").any { lowered.contains(it) }) ||
            normalized.endsWith("?")
        if (!hasExamSignal) return false

        val courseTokens = courseName
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        return courseTokens.any { lowered.contains(it) } || normalized.length >= 50
    }

    private fun preservePastExamQuestionText(original: String, corrected: String): String {
        val normalizedOriginal = original
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        val normalizedCorrected = corrected
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (normalizedOriginal.isBlank()) return normalizedCorrected
        if (!looksLikeBrokenExtractedQuestion(normalizedOriginal)) return normalizedOriginal
        return normalizedCorrected.ifBlank { normalizedOriginal }
    }

    private fun looksLikeBrokenExtractedQuestion(questionText: String): Boolean {
        val weirdCharacters = listOf("�", "□", "◻", "�")
        if (weirdCharacters.any { questionText.contains(it) }) return true
        if (Regex("[A-Za-z0-9]{1}\\s+[가-힣]{1}\\s+[A-Za-z0-9]{1}").containsMatchIn(questionText)) return true
        if (Regex("[^\\p{L}\\p{N}\\s\\p{Punct}①②③④⑤⑥⑦⑧⑨⑩]").containsMatchIn(questionText)) return true
        return false
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

    private fun validateCourseExamGenerationInputs(
        requestedStyleSet: Set<String>,
        generationMode: String,
        lectureContextSnippets: List<String>,
        styleReferenceSnippets: List<String>
    ) {
        val normalizedMode = generationMode.trim().uppercase()
        val hasLectureContext = lectureContextSnippets.any { it.isNotBlank() }
        val hasStyleReference = styleReferenceSnippets.any { it.isNotBlank() }

        when (normalizedMode) {
            "PAST_EXAM" -> {
                require(hasLectureContext) {
                    "questionStyles/requestedStyleSet=$requestedStyleSet, generationMode=PAST_EXAM requires lectureContextSnippets."
                }
                require(hasStyleReference) {
                    "questionStyles/requestedStyleSet=$requestedStyleSet, generationMode=PAST_EXAM requires styleReferenceSnippets."
                }
            }

            "PAST_EXAM_PRACTICE" -> require(hasStyleReference) {
                "questionStyles/requestedStyleSet=$requestedStyleSet, generationMode=PAST_EXAM_PRACTICE requires styleReferenceSnippets."
            }

            else -> require(hasLectureContext) {
                "questionStyles/requestedStyleSet=$requestedStyleSet, generationMode=$normalizedMode requires lectureContextSnippets."
            }
        }
    }

    private fun jsonLanguageInstruction(language: InterviewLanguage): String {
        return when (language) {
            InterviewLanguage.KO -> "아래 입력을 바탕으로 한국어 JSON만 출력하세요."
            InterviewLanguage.EN -> "Read the input below and return JSON only. feedback, bestPractice, and evidence must be written in English."
        }
    }

    private fun jsonObjectOnlyRule(language: InterviewLanguage): String {
        return when (language) {
            InterviewLanguage.KO -> "한국어 JSON 객체만 반환하고 코드블록은 금지"
            InterviewLanguage.EN -> "Return an English JSON object only and do not use code blocks"
        }
    }

    private fun sanitizeCourseExamMaxScore(rawMaxScore: Int?): Int {
        return when {
            rawMaxScore == null -> 20
            rawMaxScore <= 0 -> 20
            rawMaxScore > 100 -> 100
            else -> rawMaxScore
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

    private fun parseCourseExamEvaluationJson(
        raw: String,
        items: List<CourseExamEvaluationInput>
    ): Map<String, CourseExamEvaluationResult> {
        val root = objectMapper.readTree(raw)
        val rawResults = root["items"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val key = item["key"]?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val score = item["score"]?.asInt()
                val passScore = item["passScore"]?.asInt()
                val feedback = item.text("feedback").trim()
                key to CourseExamEvaluationResult(
                    score = score ?: 0,
                    passScore = passScore ?: 0,
                    feedback = feedback
                )
            }
            ?.toMap()
            .orEmpty()
        return normalizeCourseExamEvaluationResults(rawResults, items)
    }

    private fun normalizeStructuredText(text: String): String {
        return text.lines()
            .map { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizeCourseExamEvaluationResults(
        rawResults: Map<String, CourseExamEvaluationResult>,
        items: List<CourseExamEvaluationInput>
    ): Map<String, CourseExamEvaluationResult> {
        val normalized = linkedMapOf<String, CourseExamEvaluationResult>()
        items.forEach { item ->
            val safeMaxScore = sanitizeCourseExamMaxScore(item.maxScore)
            val expectedPassScore = (safeMaxScore * 0.6).roundToInt()
            val rawResult = rawResults[item.key]
            if (rawResult == null) {
                logger.warn("과목 시험문제 채점 결과 누락 key={} maxScore={}", item.key, safeMaxScore)
            }
            val effectiveResult = rawResult ?: CourseExamEvaluationResult(
                score = 0,
                passScore = expectedPassScore,
                feedback = "채점 결과가 누락되어 0점으로 처리했습니다."
            )
            val normalizedScore = effectiveResult.score.coerceIn(0, safeMaxScore)
            if (effectiveResult.score != normalizedScore) {
                logger.warn(
                    "과목 시험문제 채점 점수 범위 보정 key={} rawScore={} maxScore={}",
                    item.key,
                    effectiveResult.score,
                    safeMaxScore
                )
            }
            if (effectiveResult.passScore != expectedPassScore) {
                logger.warn(
                    "과목 시험문제 채점 통과점수 무시 key={} rawPassScore={} expectedPassScore={}",
                    item.key,
                    effectiveResult.passScore,
                    expectedPassScore
                )
            }
            normalized[item.key] = CourseExamEvaluationResult(
                score = normalizedScore,
                passScore = expectedPassScore,
                feedback = effectiveResult.feedback.ifBlank { "채점 근거를 생성하지 못했습니다." }
            )
        }

        val unexpectedKeys = rawResults.keys - items.map { it.key }.toSet()
        if (unexpectedKeys.isNotEmpty()) {
            logger.warn("과목 시험문제 채점 결과에 예상하지 못한 key가 포함되었습니다. keys={}", unexpectedKeys.joinToString(","))
        }
        return normalized
    }

    private fun shouldRetryCourseMaterialSummary(ex: Exception): Boolean {
        return ex is GeminiTransientException && (ex.statusCode == 429 || ex.statusCode == 503)
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

data class GeneratedCourseExamQuestion(
    val questionNo: Int,
    val questionText: String,
    val questionStyle: String,
    val canonicalAnswer: String,
    val gradingCriteria: String,
    val referenceExample: String? = null,
    val maxScore: Int = 20
)

data class CourseMaterialSummarySource(
    val fileName: String,
    val snippets: List<String>
)

data class CourseMaterialSummaryOutline(
    val title: String,
    val overviewFocus: String,
    val coreTakeawayAngles: List<String>,
    val majorTopics: List<CourseMaterialSummaryOutlineTopic>
)

data class CourseMaterialSummaryOutlineTopic(
    val title: String,
    val summaryFocus: String,
    val subtopics: List<CourseMaterialSummaryOutlineSubtopic>
)

data class CourseMaterialSummaryOutlineSubtopic(
    val title: String,
    val summaryFocus: String,
    val mustUseTerms: List<String> = emptyList()
)

data class TechnicalTermGlossaryEntry(
    val canonical: String,
    val aliases: List<String>
)

data class GeneratedCourseMaterialSummary(
    val title: String,
    val overview: String,
    val coreTakeaways: List<String>,
    val majorTopics: List<GeneratedCourseMaterialSummaryTopic>
)

data class GeneratedCourseMaterialSummaryTopic(
    val title: String,
    val summary: String,
    val subtopics: List<GeneratedCourseMaterialSummarySubtopic>
)

data class GeneratedCourseMaterialSummarySubtopic(
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val supplementaryNotes: List<String> = emptyList()
)

data class PastExamPracticeQuestionCandidate(
    val questionNo: Int,
    val questionText: String,
    val sourceFileName: String,
    val extractionMethod: String? = null
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

data class CourseExamEvaluationInput(
    val key: String,
    val questionStyle: String,
    val questionText: String,
    val canonicalAnswer: String,
    val gradingCriteria: String,
    val referenceExample: String? = null,
    val maxScore: Int,
    val userAnswer: String
)

data class CourseExamEvaluationResult(
    val score: Int,
    val passScore: Int,
    val feedback: String
)

private data class CategoryLabels(
    val jobLabel: String,
    val skillLabel: String
)
