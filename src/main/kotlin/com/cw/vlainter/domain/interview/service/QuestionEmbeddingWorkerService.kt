package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.EmbeddingProviderRouter
import com.cw.vlainter.domain.interview.entity.EmbeddingStatus
import com.cw.vlainter.domain.interview.entity.QaQuestionEmbedding
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.repository.QaQuestionEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.global.config.properties.EmbeddingWorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

@Service
class QuestionEmbeddingWorkerService(
    private val embeddingWorkerProperties: EmbeddingWorkerProperties,
    private val embeddingProviderRouter: EmbeddingProviderRouter,
    private val questionSetRepository: QaQuestionSetRepository,
    private val questionSetItemRepository: QaQuestionSetItemRepository,
    private val qaQuestionEmbeddingRepository: QaQuestionEmbeddingRepository,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.ai.embedding-worker.fixed-delay-ms:5000}")
    fun processQueuedEmbeddingSets() {
        if (!embeddingWorkerProperties.enabled) return

        val queuedSets = questionSetRepository
            .findTop10ByEmbeddingStatusAndDeletedAtIsNullOrderByEmbeddingRequestedAtAsc(EmbeddingStatus.QUEUED)
            .take(embeddingWorkerProperties.batchSize)

        if (queuedSets.isEmpty()) return
        queuedSets.forEach { queued ->
            runCatching { processSingleSet(queued.id) }
                .onFailure { ex ->
                    logger.error("질문 세트 임베딩 처리 실패(setId={}): {}", queued.id, ex.message, ex)
                }
        }
    }

    private fun processSingleSet(setId: Long) {
        val claimed = claimProcessing(setId) ?: return
        try {
            val items = questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(claimed.id)
            val questions = items.map { it.question }
                .filter { it.deletedAt == null && it.isActive }
                .distinctBy { it.id }

            var usedModel: String? = null
            var usedModelVersion: String? = null

            questions.forEach { question ->
                val payload = buildEmbeddingInput(question)
                val embedding = embeddingProviderRouter.embedText(payload)
                val modelVersion = embedding.modelVersion ?: "v1"
                upsertEmbedding(
                    question = question,
                    model = embedding.model,
                    modelVersion = modelVersion,
                    vectorLiteral = toVectorLiteral(embedding.values)
                )
                if (usedModel == null) {
                    usedModel = embedding.model
                    usedModelVersion = modelVersion
                }
            }

            markReady(
                setId = claimed.id,
                model = usedModel,
                modelVersion = usedModelVersion
            )
        } catch (ex: Exception) {
            markFailed(claimed.id, ex.message)
            throw ex
        }
    }

    private fun buildEmbeddingInput(question: com.cw.vlainter.domain.interview.entity.QaQuestion): String {
        return buildString {
            appendLine("question: ${question.questionText}")
            question.canonicalAnswer?.takeIf { it.isNotBlank() }?.let { appendLine("canonical: $it") }
            appendLine("category: ${question.category.path}")
            appendLine("difficulty: ${question.difficulty.name}")
            appendLine("tags: ${question.tagsJson}")
        }.trim()
    }

    private fun upsertEmbedding(
        question: com.cw.vlainter.domain.interview.entity.QaQuestion,
        model: String,
        modelVersion: String,
        vectorLiteral: String
    ) {
        val existing = qaQuestionEmbeddingRepository.findByQuestion_IdAndModelAndModelVersion(
            questionId = question.id,
            model = model,
            modelVersion = modelVersion
        )
        if (existing == null) {
            qaQuestionEmbeddingRepository.save(
                QaQuestionEmbedding(
                    question = question,
                    model = model,
                    modelVersion = modelVersion,
                    embedding = vectorLiteral
                )
            )
            return
        }
        existing.embedding = vectorLiteral
    }

    private fun toVectorLiteral(values: List<Double>): String {
        require(values.isNotEmpty()) { "embedding vector가 비어 있습니다." }
        return values.joinToString(prefix = "[", postfix = "]", separator = ",") {
            String.format(java.util.Locale.US, "%.8f", it)
        }
    }

    protected fun claimProcessing(setId: Long): QaQuestionSet? {
        return transactionTemplate.execute {
            val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId) ?: return@execute null
            if (set.embeddingStatus != EmbeddingStatus.QUEUED) return@execute null
            set.embeddingStatus = EmbeddingStatus.PROCESSING
            set.embeddingError = null
            set.embeddingModel = null
            set.embeddingVersion = null
            set
        }
    }

    protected fun markReady(setId: Long, model: String?, modelVersion: String?) {
        transactionTemplate.executeWithoutResult {
            val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId) ?: return@executeWithoutResult
            set.embeddingStatus = EmbeddingStatus.READY
            set.embeddedAt = OffsetDateTime.now()
            set.embeddingModel = model
            set.embeddingVersion = modelVersion
            set.embeddingError = null
        }
    }

    protected fun markFailed(setId: Long, message: String?) {
        transactionTemplate.executeWithoutResult {
            val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId) ?: return@executeWithoutResult
            set.embeddingStatus = EmbeddingStatus.FAILED
            set.embeddingError = message?.trim()?.take(1000) ?: "unknown error"
        }
    }
}
