package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.TurnEvaluationResponse
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation
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
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
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
    private val logger = LoggerFactory.getLogger(javaClass)
    private val turnLocks = ConcurrentHashMap<Long, ReentrantLock>()

    @Async
    fun evaluateTurnAsync(turnId: Long) {
        runCatching { selfProvider.getObject().evaluateTurnSync(turnId) }
            .onFailure { ex ->
                logger.warn("async turn evaluation failed turnId={} reason={}", turnId, ex.message)
                selfProvider.getObject().markFailed(turnId)
            }
    }

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(turnId: Long) {
        val turn = interviewTurnRepository.findById(turnId).orElse(null) ?: return
        turn.evaluationStatus = TurnEvaluationStatus.FAILED
    }

    private fun buildEvaluation(turn: InterviewTurn, answer: String): EvaluationResult {
        val userGeneratedQuestion = turn.sourceTag == TurnSourceTag.USER
        val resolvedAnswer = resolveAnswerContent(
            rawModelAnswer = turn.question?.canonicalAnswer ?: turn.documentQuestion?.referenceAnswer,
            rawGuideText = null
        )

        if (answer.isBlank()) {
            return EvaluationResult(
                score = BigDecimal.ZERO.setScale(2),
                feedback = "답변이 비어 있습니다. 핵심 내용을 포함해 다시 작성해 주세요.",
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
        val aiEvaluation = if (question != null) {
            interviewAiOrchestrator.evaluateTechAnswer(question, answer)
        } else if (documentQuestion != null) {
            interviewAiOrchestrator.evaluateDocumentAnswer(
                questionText = documentQuestion.questionText,
                referenceAnswer = documentQuestion.referenceAnswer,
                evidence = parseJsonArray(documentQuestion.evidenceJson),
                userAnswer = answer
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

        val canonical = resolvedAnswer.modelAnswer.orEmpty()
        val score = if (isLowEffortAnswer(answer)) {
            BigDecimal.ZERO.setScale(2)
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

        val feedback = when {
            score <= BigDecimal("10.00") -> "질문에 대한 핵심 내용이 거의 제시되지 않았습니다. 최소한 개념 정의와 적용 맥락은 포함해서 다시 답해 보세요."
            score >= BigDecimal("85.00") -> "핵심 개념을 잘 설명했습니다. 근거 사례를 한 줄 추가하면 더 좋습니다."
            score >= BigDecimal("70.00") -> "핵심은 맞지만 설명 구조가 다소 약합니다. 결론을 먼저 말하고 근거를 붙여 보세요."
            else -> "핵심 포인트 누락이 있습니다. 용어 정의와 문제 해결 흐름을 분리해 작성해 보세요."
        }

        val bestPractice = if (userGeneratedQuestion) "" else resolvedAnswer.guideText.orEmpty()

        return EvaluationResult(
            score = score,
            feedback = feedback,
            bestPractice = bestPractice,
            modelAnswer = resolvedAnswer.modelAnswer
        )
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9가-힣]+"))
            .filter { it.length >= 2 }
            .toSet()
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
            "모르겠습니다"
        )
    }

    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { objectMapper.readValue(raw, Array<String>::class.java).toList() }
            .getOrDefault(emptyList())
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
            modelAnswer = resolved.modelAnswer
        )
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
