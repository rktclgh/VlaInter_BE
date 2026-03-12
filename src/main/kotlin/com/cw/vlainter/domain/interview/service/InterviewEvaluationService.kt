package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.TurnEvaluationResponse
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import com.cw.vlainter.domain.interview.entity.TurnEvaluationStatus
import com.cw.vlainter.domain.interview.repository.InterviewTurnEvaluationRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.UserQuestionAttemptRepository
import com.cw.vlainter.domain.interview.entity.UserQuestionAttempt
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

@Service
class InterviewEvaluationService(
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val interviewTurnRepository: InterviewTurnRepository,
    private val interviewTurnEvaluationRepository: InterviewTurnEvaluationRepository,
    private val userQuestionAttemptRepository: UserQuestionAttemptRepository,
    private val userGeminiApiKeyService: UserGeminiApiKeyService,
    private val objectMapper: ObjectMapper,
    private val selfProvider: ObjectProvider<InterviewEvaluationService>
) {
    companion object {
        const val INTRO_CATEGORY = INTERVIEW_INTRO_CATEGORY
        const val INTRO_QUESTION_TEXT = INTERVIEW_INTRO_QUESTION_TEXT
        val NUMBER_REGEX = Regex("""\d""")
        val DOCUMENT_CONTEXT_SIGNALS = setOf("상황", "배경", "당시", "문제", "이슈", "프로젝트", "서비스", "요구사항")
        val DOCUMENT_TASK_SIGNALS = setOf("역할", "담당", "책임", "목표", "과제", "요구", "목적")
        val DOCUMENT_ACTION_SIGNALS = setOf("제가", "저는", "구현", "설계", "개선", "도입", "분석", "해결", "최적화", "리팩토링", "협업", "검증", "작성", "적용", "정리")
        val DOCUMENT_RESULT_SIGNALS = setOf("결과", "성과", "개선", "향상", "감소", "증가", "단축", "완료", "달성", "안정화", "배포", "출시")
        val DOCUMENT_REASONING_SIGNALS = setOf("이유", "근거", "왜냐", "그래서", "이를 위해", "때문에", "검증", "비교", "판단", "트레이드오프")
        val DOCUMENT_CONTEXT_SIGNALS_EN = setOf("situation", "background", "at the time", "problem", "issue", "project", "service", "requirement")
        val DOCUMENT_TASK_SIGNALS_EN = setOf("role", "responsibility", "goal", "task", "objective", "ownership")
        val DOCUMENT_ACTION_SIGNALS_EN = setOf("implemented", "designed", "improved", "introduced", "analyzed", "resolved", "optimized", "refactored", "collaborated", "validated", "built", "led")
        val DOCUMENT_RESULT_SIGNALS_EN = setOf("result", "outcome", "improved", "reduced", "increased", "shortened", "completed", "achieved", "stabilized", "launched")
        val DOCUMENT_REASONING_SIGNALS_EN = setOf("because", "therefore", "so that", "in order to", "reason", "evidence", "validated", "compared", "trade-off")
        val ENGLISH_WORD_REGEX = Regex("""\b[A-Za-z]{2,}\b""")
        val ENGLISH_LETTER_REGEX = Regex("""[A-Za-z]""")
        val HANGUL_REGEX = Regex("""[가-힣]""")
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val turnLocks = ConcurrentHashMap<Long, ReentrantLock>()

    @Transactional
    fun evaluateTurnSync(turnId: Long): TurnEvaluationResponse? {
        val lock = turnLocks.computeIfAbsent(turnId) { ReentrantLock() }
        return try {
            lock.withLock {
                val turn = interviewTurnRepository.findById(turnId).orElse(null) ?: return@withLock null
                val answer = turn.userAnswer?.trim().orEmpty()
                if (answer.isBlank()) return@withLock null

                val existing = interviewTurnEvaluationRepository.findByTurn_Id(turn.id)
                if (turn.evaluationStatus == TurnEvaluationStatus.DONE && existing != null) {
                    return@withLock existing.toResponse()
                }

                val evaluation = userGeminiApiKeyService.withUserApiKey(turn.session.user.id) {
                    buildEvaluation(turn, answer)
                }

                val candidate = existing?.apply {
                    totalScore = evaluation.score
                    feedback = evaluation.feedback
                    bestPractice = evaluation.bestPractice
                    rubricScoresJson = evaluation.rubricScoresJson
                    evidenceJson = evaluation.evidenceJson
                    model = evaluation.model
                    modelVersion = evaluation.modelVersion
                } ?: InterviewTurnEvaluation(
                    turn = turn,
                    totalScore = evaluation.score,
                    feedback = evaluation.feedback,
                    bestPractice = evaluation.bestPractice,
                    rubricScoresJson = evaluation.rubricScoresJson,
                    evidenceJson = evaluation.evidenceJson,
                    model = evaluation.model,
                    modelVersion = evaluation.modelVersion
                )

                val saved = saveTurnEvaluationWithConflictRecovery(turn.id, candidate)
                turn.evaluationStatus = TurnEvaluationStatus.DONE

                if (turn.question != null && shouldStoreHistory(turn.session.configJson) && !userQuestionAttemptRepository.existsByTurn_Id(turn.id)) {
                    userQuestionAttemptRepository.save(
                        UserQuestionAttempt(
                            user = turn.session.user,
                            question = turn.question,
                            session = turn.session,
                            turn = turn,
                            answerText = answer,
                            totalScore = evaluation.score,
                            feedbackSummary = evaluation.feedback
                        )
                    )
                }

                saved.toResponse()
            }
        } finally {
            if (!lock.hasQueuedThreads()) {
                turnLocks.remove(turnId, lock)
            }
        }
    }

    private fun saveTurnEvaluationWithConflictRecovery(turnId: Long, candidate: InterviewTurnEvaluation): InterviewTurnEvaluation {
        return try {
            interviewTurnEvaluationRepository.saveAndFlush(candidate)
        } catch (ex: DataIntegrityViolationException) {
            val existing = interviewTurnEvaluationRepository.findByTurn_Id(turnId)
            if (existing == null) {
                throw ex
            }
            logger.warn("turn evaluation race recovered turnId={}", turnId)
            existing.totalScore = candidate.totalScore
            existing.feedback = candidate.feedback
            existing.bestPractice = candidate.bestPractice
            existing.rubricScoresJson = candidate.rubricScoresJson
            existing.evidenceJson = candidate.evidenceJson
            existing.model = candidate.model
            existing.modelVersion = candidate.modelVersion
            interviewTurnEvaluationRepository.saveAndFlush(existing)
        }
    }

    @Transactional
    fun evaluateOutstandingTurnsSync(sessionId: Long) {
        interviewTurnRepository.findAllBySession_IdOrderByTurnNoAsc(sessionId)
            .filter { it.answeredAt != null && it.evaluationStatus != TurnEvaluationStatus.DONE }
            .forEach { selfProvider.getObject().evaluateTurnSync(it.id) }
    }

    private fun buildEvaluation(turn: InterviewTurn, answer: String): EvaluationResult {
        val sessionLanguage = resolveInterviewLanguage(turn)
        val userGeneratedQuestion = turn.sourceTag == TurnSourceTag.USER
        val resolvedAnswer = resolveAnswerContent(
            rawModelAnswer = turn.question?.canonicalAnswer ?: turn.documentQuestion?.referenceAnswer,
            rawGuideText = null
        )

        if (answer.isBlank()) {
            return EvaluationResult(
                score = BigDecimal.ZERO.setScale(2),
                feedback = if (sessionLanguage == InterviewLanguage.EN) {
                    "Your answer is empty. Please rewrite it with the key point included."
                } else {
                    "답변이 비어 있습니다. 핵심 내용을 포함해 다시 작성해 주세요."
                },
                bestPractice = if (userGeneratedQuestion) "" else resolvedAnswer.guideText
                    .orEmpty(),
                modelAnswer = resolvedAnswer.modelAnswer,
                rubricScoresJson = """{"coverage":0,"accuracy":0,"communication":0}""",
                evidenceJson = "[]",
                model = "heuristic",
                modelVersion = "v1"
            )
        }

        val question = turn.question
        val documentQuestion = turn.documentQuestion
        val aiEvaluation = if (isIntroductionTurn(turn)) {
            interviewAiOrchestrator.evaluateIntroductionAnswer(answer, sessionLanguage)
        } else if (question != null) {
            interviewAiOrchestrator.evaluateTechAnswer(question, answer, sessionLanguage)
        } else if (documentQuestion != null) {
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = documentQuestion.questionText,
                referenceAnswer = documentQuestion.referenceAnswer,
                evidence = parseJsonArray(documentQuestion.evidenceJson),
                userAnswer = answer,
                language = sessionLanguage
            )
        } else {
            null
        }
        if (aiEvaluation != null) {
            return EvaluationResult(
                score = aiEvaluation.score,
                feedback = aiEvaluation.feedback,
                bestPractice = if (userGeneratedQuestion) "" else aiEvaluation.bestPractice.ifBlank { resolvedAnswer.guideText.orEmpty() },
                modelAnswer = resolvedAnswer.modelAnswer,
                rubricScoresJson = aiEvaluation.rubricScoresJson,
                evidenceJson = aiEvaluation.evidenceJson,
                model = aiEvaluation.model,
                modelVersion = aiEvaluation.modelVersion
            )
        }

        if (documentQuestion != null) {
            return buildDocumentHeuristicEvaluation(
                questionText = documentQuestion.questionText,
                referenceAnswer = documentQuestion.referenceAnswer,
                evidence = parseJsonArray(documentQuestion.evidenceJson),
                answer = answer,
                userGeneratedQuestion = userGeneratedQuestion,
                resolvedAnswer = resolvedAnswer,
                language = sessionLanguage
            )
        }

        val canonical = resolvedAnswer.modelAnswer.orEmpty()
        val questionText = if (sessionLanguage == InterviewLanguage.EN) {
            turn.questionTextSnapshot
        } else {
            question?.questionText.orEmpty()
        }
        val score = if (isLowEffortAnswer(answer)) {
            BigDecimal.ZERO.setScale(2)
        } else if (sessionLanguage == InterviewLanguage.EN && (canonical.isBlank() || !looksMostlyEnglish(canonical))) {
            val answerTokens = tokenize(answer)
            val questionTokens = tokenize(questionText)
            val overlap = if (questionTokens.isEmpty()) 0.0 else answerTokens.intersect(questionTokens).size.toDouble() / questionTokens.size
            val communication = englishCommunicationHeuristic(answer)
            val numeric = (
                20 +
                    (overlap * 45) +
                    (communication * 0.35)
                ).coerceIn(0.0, 100.0)
            BigDecimal(numeric).setScale(2, RoundingMode.HALF_UP)
        } else if (canonical.isBlank()) {
            val lenScore = min(70, max(5, answer.length / 8))
            BigDecimal(lenScore).setScale(2)
        } else {
            val answerTokens = tokenize(answer)
            val canonicalTokens = tokenize(canonical)
            val overlap = if (canonicalTokens.isEmpty()) 0.0 else answerTokens.intersect(canonicalTokens).size.toDouble() / canonicalTokens.size
            val brevityPenalty = if (answerTokens.size < 4) 0.35 else 0.0
            val numeric = ((overlap - brevityPenalty).coerceAtLeast(0.0) * 100).coerceIn(0.0, 100.0)
            BigDecimal(numeric).setScale(2, RoundingMode.HALF_UP)
        }

        val feedback = if (sessionLanguage == InterviewLanguage.EN) {
            when {
                score <= BigDecimal("10.00") -> "Your answer barely addresses the core of the question. Restate the concept clearly and explain where it applies."
                score >= BigDecimal("85.00") -> "You explained the core idea well. Add one concrete example or trade-off to make it stronger."
                score >= BigDecimal("70.00") -> "The main point is mostly correct, but the structure is still loose. Lead with the conclusion, then support it with reasoning."
                else -> "Some key points are missing. Separate the concept, reasoning, and practical application more clearly."
            }
        } else {
            when {
                score <= BigDecimal("10.00") -> "질문에 대한 핵심 내용이 거의 제시되지 않았습니다. 최소한 개념 정의와 적용 맥락은 포함해서 다시 답해 보세요."
                score >= BigDecimal("85.00") -> "핵심 개념을 잘 설명했습니다. 근거 사례를 한 줄 추가하면 더 좋습니다."
                score >= BigDecimal("70.00") -> "핵심은 맞지만 설명 구조가 다소 약합니다. 결론을 먼저 말하고 근거를 붙여 보세요."
                else -> "핵심 포인트 누락이 있습니다. 용어 정의와 문제 해결 흐름을 분리해 작성해 보세요."
            }
        }

        val bestPractice = if (userGeneratedQuestion) "" else resolvedAnswer.guideText.orEmpty()

        return EvaluationResult(
            score = score,
            feedback = feedback,
            bestPractice = bestPractice,
            modelAnswer = resolvedAnswer.modelAnswer
        )
    }

    private fun buildDocumentHeuristicEvaluation(
        questionText: String,
        referenceAnswer: String?,
        evidence: List<String>,
        answer: String,
        userGeneratedQuestion: Boolean,
        resolvedAnswer: ResolvedAnswerContent,
        language: InterviewLanguage
    ): EvaluationResult {
        if (isLowEffortAnswer(answer)) {
            return EvaluationResult(
                score = BigDecimal.ZERO.setScale(2),
                feedback = if (language == InterviewLanguage.EN) {
                    "Your answer does not yet show the core experience or your own actions clearly enough. Rebuild it around the experience described in your document."
                } else {
                    "질문 의도에 맞는 핵심 경험과 본인의 행동이 거의 드러나지 않았습니다. 문서에 적은 경험을 기준으로 다시 답변해 보세요."
                },
                bestPractice = if (userGeneratedQuestion) "" else if (language == InterviewLanguage.EN) {
                    "Reorganize the answer in STAR order so the situation, responsibility, actions, and result are each explicit."
                } else {
                    "질문의 의도에 맞춰 당시 상황, 맡은 역할, 실제 행동, 결과를 STAR 순서로 다시 정리해 보세요."
                },
                modelAnswer = resolvedAnswer.modelAnswer,
                rubricScoresJson = """{"coverage":0,"accuracy":0,"communication":0}""",
                evidenceJson = objectMapper.writeValueAsString(listOf("low_effort_answer")),
                model = "heuristic",
                modelVersion = "document-v2"
            )
        }

        val answerTokens = tokenize(answer)
        val questionTokens = tokenize(questionText)
        val evidenceTokens = evidence.flatMap { tokenize(it) }.toSet()
        val softBenchmarkTokens = extractDocumentSoftBenchmarkTokens(referenceAnswer, evidence)
        val contextSignalsSet = if (language == InterviewLanguage.EN) DOCUMENT_CONTEXT_SIGNALS_EN else DOCUMENT_CONTEXT_SIGNALS
        val taskSignalsSet = if (language == InterviewLanguage.EN) DOCUMENT_TASK_SIGNALS_EN else DOCUMENT_TASK_SIGNALS
        val actionSignalsSet = if (language == InterviewLanguage.EN) DOCUMENT_ACTION_SIGNALS_EN else DOCUMENT_ACTION_SIGNALS
        val resultSignalsSet = if (language == InterviewLanguage.EN) DOCUMENT_RESULT_SIGNALS_EN else DOCUMENT_RESULT_SIGNALS
        val reasoningSignalsSet = if (language == InterviewLanguage.EN) DOCUMENT_REASONING_SIGNALS_EN else DOCUMENT_REASONING_SIGNALS

        val intentHits = answerTokens.intersect(questionTokens).size
        val evidenceHits = answerTokens.intersect(evidenceTokens).size
        val benchmarkHits = answerTokens.intersect(softBenchmarkTokens.toSet()).size
        val contextSignals = countSignalMatches(answer, contextSignalsSet)
        val taskSignals = countSignalMatches(answer, taskSignalsSet)
        val actionSignals = countSignalMatches(answer, actionSignalsSet)
        val resultSignals = countSignalMatches(answer, resultSignalsSet)
        val hasNumber = NUMBER_REGEX.containsMatchIn(answer)
        val hasReasoning = reasoningSignalsSet.any { answer.contains(it, ignoreCase = true) }
        val sentenceCount = answer.split(Regex("[.!?。]|\\n"))
            .map { it.trim() }
            .count { it.isNotBlank() }

        val intentScore = (
            25 +
                min(35, intentHits * 12) +
                min(20, evidenceHits * 6) +
                if (answer.length >= 80) 10 else 0 +
                if (actionSignals > 0) 10 else 0
            ).coerceIn(0, 100)

        val starScore = (
            min(25, contextSignals * 12) +
                min(20, taskSignals * 10) +
                min(30, actionSignals * 12) +
                min(25, resultSignals * 12 + if (hasNumber) 8 else 0)
            ).coerceIn(0, 100)

        val coverageScore = (intentScore * 0.6 + starScore * 0.4).toInt().coerceIn(0, 100)

        val accuracyScore = (
            20 +
                min(25, evidenceHits * 7) +
                min(15, benchmarkHits * 4) +
                if (hasNumber) 15 else 0 +
                if (hasReasoning) 15 else 0 +
                if (answer.length >= 120) 10 else 0
            ).coerceIn(0, 100)

        val communicationScore = if (language == InterviewLanguage.EN) {
            englishCommunicationHeuristic(answer).toInt().coerceIn(0, 100)
        } else {
            (
                25 +
                    min(20, sentenceCount * 6) +
                    if (answer.length in 80..600) 20 else 8 +
                    if (answer.contains("\n") || sentenceCount >= 3) 15 else 5 +
                    if (answer.trim().endsWith(".") || answer.trim().endsWith("다") || answer.trim().endsWith("요")) 10 else 0
                ).coerceIn(0, 100)
        }

        val totalScore = BigDecimal(
            (
                coverageScore * 0.45 +
                    accuracyScore * 0.35 +
                    communicationScore * 0.20
                ).coerceIn(0.0, 100.0)
        ).setScale(2, RoundingMode.HALF_UP)

        val missingStarParts = buildList {
            if (contextSignals == 0) add(if (language == InterviewLanguage.EN) "Situation" else "상황")
            if (taskSignals == 0) add(if (language == InterviewLanguage.EN) "Task/Role" else "과제/역할")
            if (actionSignals == 0) add(if (language == InterviewLanguage.EN) "Action" else "행동")
            if (resultSignals == 0 && !hasNumber) add(if (language == InterviewLanguage.EN) "Result" else "결과")
        }
        val missingKeywords = softBenchmarkTokens
            .filterNot { it in answerTokens }
            .distinct()
            .take(3)
        val evidenceNotes = buildList {
            add(
                if (language == InterviewLanguage.EN) {
                    "Question-intent keyword coverage: $intentHits"
                } else {
                    "질문 핵심 키워드 반영 ${intentHits}건"
                }
            )
            add(
                if (language == InterviewLanguage.EN) {
                    "Document evidence coverage: $evidenceHits"
                } else {
                    "문서 근거 포인트 반영 ${evidenceHits}건"
                }
            )
            if (missingStarParts.isNotEmpty()) {
                add(
                    if (language == InterviewLanguage.EN) {
                        "Missing STAR elements: ${missingStarParts.joinToString(", ")}"
                    } else {
                        "STAR 누락 요소: ${missingStarParts.joinToString(", ")}"
                    }
                )
            }
            if (!hasNumber) {
                add(if (language == InterviewLanguage.EN) "Missing measurable result evidence" else "수치/결과 근거 부족")
            }
        }

        val feedback = buildString {
            append(
                when {
                    coverageScore >= 80 -> if (language == InterviewLanguage.EN) "Your answer is largely aligned with the question intent." else "질문 의도에는 대체로 잘 맞게 답했습니다."
                    coverageScore >= 60 -> if (language == InterviewLanguage.EN) "The relevant experience is visible, but the answer still needs a sharper focus." else "질문 의도와 관련된 경험은 보이지만, 핵심 초점이 조금 더 선명해야 합니다."
                    else -> if (language == InterviewLanguage.EN) "The answer focus does not align tightly enough with the core experience the question is asking for." else "질문이 묻는 핵심 경험과 답변 초점이 충분히 맞물리지 않았습니다."
                }
            )
            append(' ')
            append(
                when {
                    starScore >= 75 -> if (language == InterviewLanguage.EN) "The flow of situation, role, action, and result is fairly natural." else "상황, 역할, 행동, 결과 흐름도 비교적 자연스럽게 드러납니다."
                    starScore >= 50 -> if (language == InterviewLanguage.EN) "Some STAR structure is present, but missing pieces still reduce persuasiveness." else "STAR 흐름은 일부 보이지만, 빠진 요소가 있어 설득력이 다소 약합니다."
                    else -> if (language == InterviewLanguage.EN) "The STAR structure is weak, so the context and your contribution are not yet clear enough for an interview answer." else "STAR 구조가 약해 면접 답변으로 들었을 때 경험의 맥락과 본인 기여도가 충분히 드러나지 않습니다."
                }
            )
            append(' ')
            append(
                when {
                    accuracyScore >= 75 -> if (language == InterviewLanguage.EN) "The technical explanation and supporting evidence are reasonably convincing." else "기술적 설명과 근거도 비교적 설득력 있습니다."
                    accuracyScore >= 55 -> if (language == InterviewLanguage.EN) "The technical explanation is understandable, but the reasoning and outcome evidence need reinforcement." else "기술적 설명은 가능하지만, 선택 이유나 성과 근거를 더 보강할 필요가 있습니다."
                    else -> if (language == InterviewLanguage.EN) "The answer lacks enough reasoning, validation, or result evidence to feel fully credible." else "기술 선택 이유, 검증 근거, 성과 설명이 부족해 답변의 신뢰도가 떨어집니다."
                }
            )
        }

        val bestPractice = if (userGeneratedQuestion) {
            ""
        } else {
            buildString {
                if (missingStarParts.isNotEmpty()) {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "In your next answer, make ${missingStarParts.joinToString(", ")} more explicit so the STAR flow feels complete. "
                        } else {
                            "다음 답변에서는 ${missingStarParts.joinToString(", ")} 요소를 더 분명히 넣어 STAR 흐름을 완성해 보세요. "
                        }
                    )
                } else {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "The overall flow is acceptable, so tighten the link between your actions and results to improve impact. "
                        } else {
                            "현재 답변 흐름은 나쁘지 않으니, 행동과 결과를 더 압축적으로 연결해 전달력을 높여 보세요. "
                        }
                    )
                }
                if (missingKeywords.isNotEmpty()) {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "Adding concrete document-specific details such as ${missingKeywords.joinToString(", ")} will make the link to the question much clearer. "
                        } else {
                            "${missingKeywords.joinToString(", ")} 같은 문서 맥락의 구체 요소를 넣으면 질문과의 연결성이 더 선명해집니다. "
                        }
                    )
                }
                if (!hasNumber) {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "If possible, add measurable evidence such as metrics, user impact, performance changes, or a concrete outcome."
                        } else {
                            "가능하면 수치, 사용자 영향, 성능 변화, 완료 결과처럼 확인 가능한 근거를 함께 제시하세요."
                        }
                    )
                } else if (!hasReasoning) {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "When you describe the result, also explain why you made that decision."
                        } else {
                            "결과를 말할 때는 왜 그런 선택을 했는지 판단 근거도 같이 설명해 주세요."
                        }
                    )
                } else {
                    append(
                        if (language == InterviewLanguage.EN) {
                            "Emphasize the part you personally decided and executed more directly and more concisely."
                        } else {
                            "특히 본인이 직접 판단하고 실행한 부분을 더 짧고 선명하게 강조하면 좋습니다."
                        }
                    )
                }
            }.trim()
        }

        return EvaluationResult(
            score = totalScore,
            feedback = feedback,
            bestPractice = bestPractice,
            modelAnswer = resolvedAnswer.modelAnswer,
            rubricScoresJson = objectMapper.writeValueAsString(
                mapOf(
                    "coverage" to coverageScore,
                    "accuracy" to accuracyScore,
                    "communication" to communicationScore
                )
            ),
            evidenceJson = objectMapper.writeValueAsString(evidenceNotes),
            model = "heuristic",
            modelVersion = "document-v2"
        )
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun countSignalMatches(answer: String, signals: Set<String>): Int {
        return signals.count { answer.contains(it, ignoreCase = true) }
    }

    private fun extractDocumentSoftBenchmarkTokens(referenceAnswer: String?, evidence: List<String>): List<String> {
        val stopwords = setOf(
            "질문", "답변", "경험", "프로젝트", "서비스", "사용자", "당시", "이후", "정도",
            "통해", "관련", "기반", "설명", "대한", "문서", "면접", "저는", "제가", "그리고"
        )
        return buildList {
            addAll(tokenize(referenceAnswer.orEmpty()))
            evidence.forEach { addAll(tokenize(it)) }
        }.filterNot { it in stopwords }
            .distinct()
            .take(8)
    }

    private fun isLowEffortAnswer(answer: String): Boolean {
        val normalized = answer.trim().lowercase()
        if (normalized.length <= 6) return true
        return normalized in setOf(
            "모르겠어요",
            "잘 모르겠습니다",
            "기억이 안납니다",
            "생각이 안납니다",
            "잘 모르겠어요",
            "모르겠습니다",
            "i don't know",
            "not sure",
            "i am not sure",
            "no idea"
        )
    }

    private fun resolveInterviewLanguage(turn: InterviewTurn): InterviewLanguage {
        val rawConfig = turn.session.configJson
        if (rawConfig.isBlank()) return InterviewLanguage.KO
        val root = runCatching { objectMapper.readTree(rawConfig) }.getOrNull() ?: return InterviewLanguage.KO
        val raw = root.path("meta").path("language").asText().trim().uppercase()
        return runCatching { InterviewLanguage.valueOf(raw) }.getOrDefault(InterviewLanguage.KO)
    }

    private fun englishCommunicationHeuristic(answer: String): Double {
        val sentenceCount = answer.split(Regex("[.!?\\n]"))
            .map { it.trim() }
            .count { it.isNotBlank() }
        val englishWords = ENGLISH_WORD_REGEX.findAll(answer).count()
        val englishLetters = ENGLISH_LETTER_REGEX.findAll(answer).count()
        val hangulLetters = HANGUL_REGEX.findAll(answer).count()
        val punctuationEnding = answer.trim().lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true
        return (
            20 +
                min(25, sentenceCount * 7) +
                min(20, englishWords) +
                if (englishLetters >= hangulLetters * 2) 15 else 4 +
                if (punctuationEnding) 10 else 3 +
                if (answer.length in 60..800) 10 else 4
            ).coerceIn(0, 100).toDouble()
    }

    private fun looksMostlyEnglish(text: String): Boolean {
        val englishLetters = ENGLISH_LETTER_REGEX.findAll(text).count()
        val hangulLetters = HANGUL_REGEX.findAll(text).count()
        return englishLetters >= max(8, hangulLetters * 2)
    }

    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { objectMapper.readValue(raw, Array<String>::class.java).toList() }
            .getOrDefault(emptyList())
    }

    private fun isIntroductionTurn(turn: InterviewTurn): Boolean {
        return turn.question == null &&
            turn.documentQuestion == null &&
            turn.categorySnapshot == INTRO_CATEGORY &&
            turn.questionTextSnapshot in setOf(
                INTRO_QUESTION_TEXT,
                interviewAiOrchestrator.localizedIntroQuestion(InterviewLanguage.EN)
            )
    }

    private fun InterviewTurnEvaluation.toResponse(): TurnEvaluationResponse {
        val resolved = resolveAnswerContent(
            rawModelAnswer = turn.question?.canonicalAnswer ?: turn.documentQuestion?.referenceAnswer,
            rawGuideText = bestPractice
        )
        return TurnEvaluationResponse(
            score = totalScore,
            feedback = feedback,
            bestPractice = if (turn.sourceTag == TurnSourceTag.USER) "" else resolved.guideText ?: bestPractice,
            modelAnswer = resolved.modelAnswer,
            providerUsed = resolveProviderName(model)
        )
    }

    private fun resolveProviderName(model: String?): String? {
        val normalized = model?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        return when {
            normalized == "heuristic" -> "HEURISTIC"
            normalized.contains("gemini") -> "GEMINI"
            normalized.contains("nova") || normalized.contains("bedrock") -> "BEDROCK"
            else -> null
        }
    }

    private fun shouldStoreHistory(configJson: String?): Boolean {
        if (configJson.isNullOrBlank()) return true
        val root = runCatching { objectMapper.readTree(configJson) }.getOrNull() ?: return true
        val saveHistoryNode = root.path("meta").path("saveHistory")
        return !saveHistoryNode.isBoolean || saveHistoryNode.asBoolean()
    }

    private data class EvaluationResult(
        val score: BigDecimal,
        val feedback: String,
        val bestPractice: String,
        val modelAnswer: String? = null,
        val rubricScoresJson: String = "{}",
        val evidenceJson: String = "[]",
        val model: String? = null,
        val modelVersion: String? = null
    )
}
