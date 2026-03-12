package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.AiRoutingContextHolder
import com.cw.vlainter.domain.interview.ai.EmbeddingProviderRouter
import com.cw.vlainter.domain.interview.ai.GeminiTransientException
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.DocumentIngestionResponse
import com.cw.vlainter.domain.interview.dto.InterviewHistoryDocumentResponse
import com.cw.vlainter.domain.interview.dto.InterviewQuestionResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionHistoryResponse
import com.cw.vlainter.domain.interview.dto.ReadyDocumentResponse
import com.cw.vlainter.domain.interview.dto.ResumeInterviewSessionResponse
import com.cw.vlainter.domain.interview.dto.StartMockInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.entity.DocChunkEmbedding
import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.interview.entity.DocumentQuestion
import com.cw.vlainter.domain.interview.entity.DocumentQuestionSet
import com.cw.vlainter.domain.interview.entity.InterviewMode
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.RevealPolicy
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.interview.repository.DocumentQuestionRepository
import com.cw.vlainter.domain.interview.repository.DocumentQuestionSetRepository
import com.cw.vlainter.domain.interview.repository.InterviewSessionRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.OcrProperties
import com.cw.vlainter.global.config.properties.S3Properties
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit

@Service
class DocumentInterviewService(
    private val userRepository: UserRepository,
    private val userFileRepository: UserFileRepository,
    private val documentIngestionJobRepository: DocumentIngestionJobRepository,
    private val docChunkEmbeddingRepository: DocChunkEmbeddingRepository,
    private val documentQuestionSetRepository: DocumentQuestionSetRepository,
    private val documentQuestionRepository: DocumentQuestionRepository,
    private val questionRepository: QaQuestionRepository,
    private val categoryRepository: QaCategoryRepository,
    private val categoryContextResolver: InterviewCategoryContextResolver,
    private val jobSkillCatalogService: JobSkillCatalogService,
    private val questionSetRepository: QaQuestionSetRepository,
    private val questionSetItemRepository: QaQuestionSetItemRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    private val interviewTurnRepository: InterviewTurnRepository,
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val aiRoutingContextHolder: AiRoutingContextHolder,
    private val embeddingProviderRouter: EmbeddingProviderRouter,
    private val objectMapper: ObjectMapper,
    private val s3Client: S3Client,
    private val s3Properties: S3Properties,
    private val ocrProperties: OcrProperties,
    private val selfProvider: ObjectProvider<DocumentInterviewService>,
    private val userGeminiApiKeyService: UserGeminiApiKeyService
) {
    companion object {
        private const val AI_GENERATED_SET_DESCRIPTION = "모의면접 시작 시 자동 생성된 기술 문답"
        private const val INTRO_CATEGORY = INTERVIEW_INTRO_CATEGORY
        private const val INTRO_QUESTION_TEXT = INTERVIEW_INTRO_QUESTION_TEXT
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    @Volatile
    private var availableTesseractLanguages: Set<String>? = null

    @Transactional(readOnly = true)
    fun getReadyDocuments(principal: AuthPrincipal): List<ReadyDocumentResponse> {
        val actor = loadActiveUser(principal.userId)
        return userFileRepository.findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(actor.id)
            .asSequence()
            .filter { it.fileType.isInterviewDocument() }
            .mapNotNull { file ->
                val latestJob = documentIngestionJobRepository.findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(actor.id, file.id)
                    ?: return@mapNotNull null
                if (latestJob.status != DocumentIngestionStatus.READY) return@mapNotNull null
                ReadyDocumentResponse(
                    fileId = file.id,
                    fileName = file.originalFileName,
                    fileType = file.fileType.name,
                    status = latestJob.status,
                    chunkCount = latestJob.chunkCount,
                    extractionMethod = extractExtractionMethod(latestJob.metadataJson),
                    ocrUsed = extractExtractionMethod(latestJob.metadataJson) == "OCR_TESSERACT",
                    lastIngestedAt = latestJob.finishedAt
                )
            }
            .toList()
    }

    @Transactional
    fun ingestDocument(principal: AuthPrincipal, fileId: Long): DocumentIngestionResponse {
        val actor = loadActiveUser(principal.userId)
        userGeminiApiKeyService.assertGeminiApiKeyConfigured(actor.id)
        val file = loadOwnedInterviewDocument(actor.id, fileId)

        val existing = documentIngestionJobRepository.findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(actor.id, file.id)
        if (existing != null && (existing.status == DocumentIngestionStatus.QUEUED || existing.status == DocumentIngestionStatus.PROCESSING)) {
            return toIngestionResponse(existing)
        }

        val target = existing ?: DocumentIngestionJob(
            userId = actor.id,
            documentFileId = file.id,
            status = DocumentIngestionStatus.QUEUED
        )

        target.status = DocumentIngestionStatus.QUEUED
        target.errorMessage = null
        target.startedAt = null
        target.finishedAt = null
        target.requestedAt = OffsetDateTime.now()

        val job = try {
            documentIngestionJobRepository.saveAndFlush(target)
        } catch (ex: DataIntegrityViolationException) {
            val recovered = documentIngestionJobRepository.findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(actor.id, file.id)
            if (recovered != null) {
                recovered
            } else {
                throw ex
            }
        }

        runAfterCommit {
            selfProvider.getObject().ingestDocumentAsync(job.id)
        }

        return toIngestionResponse(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markIngestionProcessing(jobId: Long): DocumentIngestionJob? {
        val job = documentIngestionJobRepository.findById(jobId).orElse(null) ?: return null
        if (job.status == DocumentIngestionStatus.CANCELLED || job.status == DocumentIngestionStatus.READY) return null
        job.status = DocumentIngestionStatus.PROCESSING
        job.startedAt = OffsetDateTime.now()
        job.finishedAt = null
        job.errorMessage = null
        return documentIngestionJobRepository.save(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeIngestion(
        jobId: Long,
        userId: Long,
        fileId: Long,
        extractionMethod: String,
        ocrLanguages: String?,
        text: String,
        embeddings: List<DocChunkEmbedding>
    ) {
        val job = documentIngestionJobRepository.findById(jobId).orElse(null) ?: return
        if (job.status == DocumentIngestionStatus.CANCELLED) return

        val stillExists = userFileRepository.findByIdAndUser_IdAndDeletedAtIsNull(fileId, userId) != null
        if (!stillExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "분석 중 문서가 삭제되었습니다.")
        }

        docChunkEmbeddingRepository.deleteAllByUserIdAndUserFileId(userId, fileId)
        docChunkEmbeddingRepository.saveAll(embeddings)

        val firstEmbedding = embeddings.first()
        job.status = DocumentIngestionStatus.READY
        job.errorMessage = null
        job.parserName = "pdfbox"
        job.embeddingModel = firstEmbedding.model
        job.embeddingVersion = firstEmbedding.modelVersion
        job.chunkCount = embeddings.size
        job.metadataJson = objectMapper.writeValueAsString(
            mapOf(
                "characterCount" to text.length,
                "preview" to text.take(500),
                "extractionMethod" to extractionMethod,
                "ocrLanguages" to ocrLanguages
            )
        )
        job.finishedAt = OffsetDateTime.now()
        documentIngestionJobRepository.save(job)
    }

    @Async
    fun ingestDocumentAsync(jobId: Long) {
        runCatching { selfProvider.getObject().ingestDocumentSync(jobId) }
            .onFailure { ex ->
                logger.warn("document ingestion async failed jobId={} reason={}", jobId, ex.message)
                selfProvider.getObject().markIngestionFailed(jobId, ex.message)
            }
    }

    fun ingestDocumentSync(jobId: Long) {
        val job = selfProvider.getObject().markIngestionProcessing(jobId) ?: return
        val file = loadOwnedInterviewDocument(job.userId, job.documentFileId)

        try {
            val extracted = extractPdfText(file)
            val text = extracted.text
            val chunks = splitIntoChunks(text)
            if (chunks.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "문서에서 분석 가능한 텍스트를 찾지 못했습니다.")
            }

            val embeddings = userGeminiApiKeyService.withUserApiKey(job.userId) {
                chunks.mapIndexed { index, chunk ->
                    val embedded = embeddingProviderRouter.embedText(chunk)
                    DocChunkEmbedding(
                        userFileId = file.id,
                        userId = job.userId,
                        chunkNo = index + 1,
                        chunkText = chunk,
                        tokenCount = estimateTokenCount(chunk),
                        model = embedded.model,
                        modelVersion = embedded.modelVersion ?: "unknown",
                        embedding = embedded.values.toVectorLiteral(),
                        metadataJson = objectMapper.writeValueAsString(
                            mapOf(
                                "fileType" to file.fileType.name,
                                "originalFileName" to file.originalFileName
                            )
                        )
                    )
                }
            }

            selfProvider.getObject().completeIngestion(
                jobId = job.id,
                userId = job.userId,
                fileId = file.id,
                extractionMethod = extracted.method,
                ocrLanguages = extracted.ocrLanguages,
                text = text,
                embeddings = embeddings
            )
        } catch (ex: Exception) {
            logger.warn("document ingestion failed fileId={} reason={}", file.id, ex.message)
            throw ex
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markIngestionFailed(jobId: Long, message: String?) {
        val job = documentIngestionJobRepository.findById(jobId).orElse(null) ?: return
        if (job.status == DocumentIngestionStatus.READY || job.status == DocumentIngestionStatus.CANCELLED) return
        job.status = DocumentIngestionStatus.FAILED
        job.errorMessage = message?.take(1000)
        job.finishedAt = OffsetDateTime.now()
        documentIngestionJobRepository.save(job)
    }

    @Transactional
    fun startMockInterview(principal: AuthPrincipal, request: StartMockInterviewRequest): StartTechInterviewResponse {
        aiRoutingContextHolder.reset()
        try {
            val actor = loadActiveUser(principal.userId)
            userGeminiApiKeyService.assertGeminiApiKeyConfigured(actor.id)
            val documentIds = request.documentFileIds.distinct()
            val files = documentIds.map { loadOwnedInterviewDocument(actor.id, it) }
            if (files.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "선택된 문서가 없습니다.")
            }

            files.forEach { file ->
                val latestJob = documentIngestionJobRepository.findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(actor.id, file.id)
                if (latestJob?.status != DocumentIngestionStatus.READY) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "분석 완료된 문서만 면접에 사용할 수 있습니다: ${file.originalFileName}")
                }
            }

            val selectedQuestionSet = request.questionSetId?.let { loadOwnedUserQuestionSet(actor.id, it) }
            val selectedSetQuestions = selectedQuestionSet
                ?.let { questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(it.id) }
                ?.map { it.question }
                .orEmpty()
            if (selectedQuestionSet != null && selectedSetQuestions.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 질문 세트에 사용할 기술 질문이 없습니다.")
            }

            val techContexts = if (selectedQuestionSet == null) resolveRequestedTechContexts(request) else emptyList()
            techContexts.forEach { context ->
                jobSkillCatalogService.ensureCatalog(context.jobName, context.skillName)
            }

            val requestedCount = request.questionCount.coerceAtLeast(5).coerceAtMost(20)
            val desiredTechTarget = max(1, (requestedCount * 0.4).roundToInt())
            val (techCandidates, generatedQuestions) = userGeminiApiKeyService.withUserApiKey(actor.id) {
                val resolvedTechCandidates = if (selectedSetQuestions.isNotEmpty()) {
                    selectedSetQuestions
                } else {
                    resolveOrGenerateTechCandidates(
                        actor = actor,
                        contexts = techContexts,
                        difficulty = request.difficulty,
                        requestedCount = desiredTechTarget,
                        language = request.language
                    )
                }
                val techTarget = if (resolvedTechCandidates.isNotEmpty()) {
                    min(resolvedTechCandidates.size, desiredTechTarget)
                } else {
                    0
                }
                val documentTarget = max(1, requestedCount - techTarget)
                val resolvedDocumentQuestions = generateDocumentQuestions(
                    actor = actor,
                    files = files,
                    difficulty = request.difficulty,
                    questionCount = documentTarget,
                    language = request.language
                )
                resolvedTechCandidates to resolvedDocumentQuestions
            }

            val techTarget = if (techCandidates.isNotEmpty()) {
                min(techCandidates.size, max(1, (requestedCount * 0.4).roundToInt()))
            } else {
                0
            }
            val selectedTech = techCandidates.shuffled().take(techTarget)
            val techMetaJobName = selectedQuestionSet?.jobName
                ?: techContexts.firstOrNull()?.jobName
                ?: selectedTech.firstOrNull()?.jobName
                ?: selectedTech.firstOrNull()?.category?.parent?.name?.trim()
                ?: request.jobName?.trim()
                ?: "직무"
            val techMetaSkillNames = if (selectedQuestionSet != null) {
                selectedSetQuestions.mapNotNull { question ->
                    question.skillName?.trim()?.takeIf { it.isNotBlank() } ?: question.category.name.trim().takeIf { it.isNotBlank() }
                }.distinctBy { it.lowercase() }
            } else {
                techContexts.map { it.skillName }
            }
            val primaryCategoryId = if (selectedQuestionSet != null && techMetaSkillNames.size == 1) {
                selectedSetQuestions.firstOrNull()?.category?.id
            } else if (techContexts.size == 1) {
                techContexts.first().category.id
            } else {
                null
            }
            val queue = buildList {
                if (request.includeSelfIntroduction) {
                    add(InterviewPracticeService.QuestionRef(InterviewQuestionKind.INTRO, 0))
                }
                addAll(generatedQuestions.map { InterviewPracticeService.QuestionRef(InterviewQuestionKind.DOCUMENT, it.id) })
                addAll(selectedTech.map { InterviewPracticeService.QuestionRef(InterviewQuestionKind.TECH, it.id) })
            }.let { refs ->
                val intro = refs.firstOrNull()?.takeIf { it.kind == InterviewQuestionKind.INTRO }
                val remaining = if (intro != null) refs.drop(1).shuffled() else refs.shuffled()
                if (intro != null) listOf(intro) + remaining else remaining
            }
            if (queue.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "면접 질문 생성에 실패했습니다.")
            }

            val interviewMode = when {
                generatedQuestions.isNotEmpty() && selectedTech.isNotEmpty() -> InterviewMode.MIXED
                generatedQuestions.isNotEmpty() -> InterviewMode.DOC
                else -> InterviewMode.TECH
            }
            val session = interviewSessionRepository.save(
                InterviewSession(
                    user = actor,
                    mode = interviewMode,
                    status = InterviewStatus.IN_PROGRESS,
                    revealPolicy = RevealPolicy.END_ONLY,
                    configJson = objectMapper.writeValueAsString(
                        mapOf(
                            "queue" to queue,
                            "cursor" to 1,
                            "meta" to mapOf(
                                "questionCount" to queue.size,
                                "requestedQuestionCount" to requestedCount,
                                "includeSelfIntroduction" to request.includeSelfIntroduction,
                                "difficulty" to request.difficulty?.name,
                                "difficultyRating" to difficultyToRating(request.difficulty),
                                "language" to request.language.name,
                                "categoryId" to primaryCategoryId,
                                "categoryName" to techMetaSkillNames.joinToString(", "),
                                "jobName" to techMetaJobName,
                                "questionSetId" to selectedQuestionSet?.id,
                                "providerUsed" to aiRoutingContextHolder.snapshot().providerUsed?.name,
                                "fallbackDepth" to aiRoutingContextHolder.snapshot().fallbackDepth,
                                "selectedDocuments" to files.map { file ->
                                    mapOf(
                                        "fileId" to file.id,
                                        "fileType" to file.fileType.name,
                                        "label" to file.originalFileName,
                                        "ocrUsed" to isOcrDocument(actor.id, file.id)
                                    )
                                }
                            )
                        )
                    )
                )
            )

            val firstTurn = createTurnFromRef(session, queue.first())
            val routingSnapshot = aiRoutingContextHolder.snapshot()
            logger.info(
                "mock interview session created sessionId={} providers={} providerUsed={} fallbackDepth={}",
                session.id,
                routingSnapshot.usedProviders,
                routingSnapshot.providerUsed,
                routingSnapshot.fallbackDepth
            )
            return StartTechInterviewResponse(
                sessionId = session.id,
                status = session.status.name,
                currentQuestion = toInterviewQuestionResponse(firstTurn),
                hasNext = queue.size > 1,
                language = request.language.name,
                providerUsed = routingSnapshot.providerUsed?.name,
                fallbackDepth = routingSnapshot.fallbackDepth
            )
        } finally {
            aiRoutingContextHolder.clear()
        }
    }

    private fun generateDocumentQuestions(
        actor: User,
        files: List<UserFile>,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        language: InterviewLanguage
    ): List<DocumentQuestion> {
        val allocation = distribute(questionCount, files.size)
        val results = mutableListOf<DocumentQuestion>()
        val skippedReasons = mutableListOf<String>()
        var lastGeminiTransient: GeminiTransientException? = null

        files.forEachIndexed { index, file ->
            val targetCount = allocation[index]
            if (targetCount <= 0) return@forEachIndexed

            val chunks = docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(actor.id, file.id)
                .map { it.chunkText }
            if (chunks.isEmpty()) {
                val reason = "문서 분석 결과 없음(fileId=${file.id}, name=${file.originalFileName})"
                logger.warn("document question generation skipped: {}", reason)
                skippedReasons += reason
                return@forEachIndexed
            }

            val snippets = retrievePromptSnippets(actor.id, file, difficulty, targetCount)
            val snippetValidation = interviewAiOrchestrator.validateEvidenceSnippets(
                fileTypeLabel = file.fileType.toPromptLabel(),
                snippets = snippets
            )
            val strictValidatedSnippets = snippetValidation.acceptedSnippets
                .map(::sanitizePromptSnippet)
                .filter(::isUsablePromptSnippet)
                .distinct()
                .take(max(2, min(targetCount + 1, 5)))

            val relaxedValidatedSnippets = if (strictValidatedSnippets.isNotEmpty()) {
                strictValidatedSnippets
            } else {
                (snippets + fallbackPromptSnippets(chunks))
                    .map(::sanitizePromptSnippet)
                    .filter(::isUsablePromptSnippet)
                    .distinct()
                    .take(max(2, min(targetCount + 1, 5)))
            }
            if (relaxedValidatedSnippets.isEmpty()) {
                val reason = "발췌 검증/완화 모두 실패(fileId=${file.id}, name=${file.originalFileName})"
                logger.warn("document question generation skipped: {}", reason)
                skippedReasons += reason
                return@forEachIndexed
            }
            val usedRelaxedFallback = strictValidatedSnippets.isEmpty()
            if (usedRelaxedFallback) {
                logger.warn(
                    "snippet validation strict pass failed -> relaxed fallback applied fileId={} name={}",
                    file.id,
                    file.originalFileName
                )
            }

            val generated = runCatching {
                interviewAiOrchestrator.generateDocumentQuestions(
                    fileTypeLabel = file.fileType.toPromptLabel(),
                    difficulty = difficulty,
                    questionCount = targetCount,
                    contextSnippets = relaxedValidatedSnippets,
                    language = language
                )
            }.onFailure { ex ->
                if (ex is GeminiTransientException) {
                    lastGeminiTransient = ex
                }
            }.onFailure { ex ->
                logger.warn(
                    "document question generation failed fileId={} name={} reason={}",
                    file.id,
                    file.originalFileName,
                    ex.message
                )
                skippedReasons += "질문 생성 실패(fileId=${file.id}, name=${file.originalFileName}): ${ex.message}"
            }.getOrElse { return@forEachIndexed }
            if (generated.isEmpty()) {
                skippedReasons += "질문 생성 결과 비어있음(fileId=${file.id}, name=${file.originalFileName})"
                return@forEachIndexed
            }

            val set = documentQuestionSetRepository.save(
                DocumentQuestionSet(
                    userId = actor.id,
                    documentFileId = file.id,
                    ingestionJobId = documentIngestionJobRepository
                        .findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(actor.id, file.id)
                        ?.id,
                    title = "${file.originalFileName} 기반 면접 질문",
                    sourceFileType = file.fileType,
                    generationContextJson = objectMapper.writeValueAsString(
                        mapOf(
                            "difficulty" to difficulty?.name,
                            "requestedQuestionCount" to targetCount,
                            "generatedAt" to OffsetDateTime.now().toString(),
                            "snippetValidation" to mapOf(
                                "inputCount" to snippets.size,
                                "acceptedCount" to relaxedValidatedSnippets.size,
                                "strictAcceptedCount" to strictValidatedSnippets.size,
                                "usedRelaxedFallback" to usedRelaxedFallback,
                                "details" to snippetValidation.details.map {
                                    mapOf(
                                        "index" to it.index,
                                        "accepted" to it.accepted,
                                        "reason" to it.reason
                                    )
                                }
                            )
                        )
                    ),
                    questionCount = generated.size
                )
            )

            val persisted = documentQuestionRepository.saveAll(
                generated.mapIndexed { questionIndex, item ->
                    DocumentQuestion(
                        setId = set.id,
                        userId = actor.id,
                        documentFileId = file.id,
                        questionNo = questionIndex + 1,
                        questionText = item.questionText,
                        questionType = item.questionType.take(30),
                        difficulty = difficulty?.name,
                        referenceAnswer = item.referenceAnswer,
                        evidenceJson = objectMapper.writeValueAsString(item.evidence)
                    )
                }
            )
            results += persisted
        }

        val transientError = lastGeminiTransient
        if (results.isEmpty() && transientError != null) {
            throw toGeminiOverloadException(transientError)
        }
        if (results.isEmpty()) {
            val reason = skippedReasons.firstOrNull()?.let { "($it)" } ?: ""
            throw ResponseStatusException(HttpStatus.CONFLICT, "문서 질문 생성에 사용할 유효 근거가 부족합니다. $reason".trim())
        }
        if (results.size < questionCount) {
            logger.warn(
                "document question generation partial success requested={} generated={} skipped={}",
                questionCount,
                results.size,
                skippedReasons.size
            )
        }
        return results.take(questionCount)
    }

    @Transactional(readOnly = true)
    fun getMockSessionHistory(principal: AuthPrincipal): List<InterviewSessionHistoryResponse> {
        return interviewSessionRepository
            .findAllByUser_IdAndModeInOrderByCreatedAtDesc(principal.userId, listOf(InterviewMode.DOC, InterviewMode.MIXED))
            .map { session ->
                val root = runCatching { objectMapper.readTree(session.configJson) }.getOrNull()
                val meta = root?.get("meta")
                val turns = interviewTurnRepository.findAllBySession_IdOrderByTurnNoAsc(session.id)
                val queueSize = root?.get("queue")?.takeIf { it.isArray }?.size() ?: 0
                val documents = meta?.get("selectedDocuments")
                    ?.takeIf { it.isArray }
                    ?.map { item ->
                        InterviewHistoryDocumentResponse(
                            fileId = item["fileId"]?.takeIf { it.canConvertToLong() }?.asLong(),
                            fileType = item["fileType"]?.asText(),
                            label = item["label"]?.asText()?.trim().orEmpty(),
                            ocrUsed = item["ocrUsed"]?.asBoolean() == true
                        )
                    }
                    ?.filter { it.label.isNotBlank() }
                    ?: emptyList()

                InterviewSessionHistoryResponse(
                    sessionId = session.id,
                    status = session.status.name,
                    mode = session.mode.name,
                    language = meta?.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: InterviewLanguage.KO.name,
                    questionCount = meta?.get("questionCount")?.asInt() ?: max(queueSize, turns.size),
                    difficulty = meta?.get("difficulty")?.asText() ?: turns.firstOrNull()?.difficulty,
                    difficultyRating = meta?.get("difficultyRating")?.asInt()
                        ?: difficultyToRating(turns.firstOrNull()?.difficulty?.let { runCatching { QuestionDifficulty.valueOf(it) }.getOrNull() }),
                    categoryId = meta?.get("categoryId")?.takeIf { it.canConvertToLong() }?.asLong()
                        ?: turns.firstOrNull()?.category?.id,
                    categoryName = meta?.get("categoryName")?.asText()?.takeIf { it.isNotBlank() }
                        ?: turns.firstOrNull()?.categorySnapshot,
                    jobName = meta?.get("jobName")?.asText()?.takeIf { it.isNotBlank() },
                    selectedDocuments = documents,
                    startedAt = session.startedAt,
                    finishedAt = session.finishedAt
                )
            }
    }

    @Transactional(readOnly = true)
    fun getLatestIncompleteMockSession(principal: AuthPrincipal): ResumeInterviewSessionResponse? {
        val latestSession = interviewSessionRepository
            .findAllByUser_IdAndModeInOrderByUpdatedAtDescCreatedAtDesc(
                userId = principal.userId,
                modes = listOf(InterviewMode.DOC, InterviewMode.MIXED)
            )
            .firstOrNull()
            ?: return null
        if (latestSession.status != InterviewStatus.IN_PROGRESS) {
            return null
        }

        val root = runCatching { objectMapper.readTree(latestSession.configJson) }.getOrNull() ?: return null
        val meta = root.get("meta")
        val selectedDocumentsNode = meta?.get("selectedDocuments")
        val selectedDocuments = when {
            selectedDocumentsNode == null -> emptyList()
            selectedDocumentsNode.isArray -> selectedDocumentsNode
                .mapNotNull { item -> item.toInterviewHistoryDocumentResponse() }
            selectedDocumentsNode.isObject -> selectedDocumentsNode
                .fieldNames()
                .asSequence()
                .mapNotNull { fieldName -> selectedDocumentsNode.get(fieldName)?.toInterviewHistoryDocumentResponse() }
                .toList()
            else -> emptyList()
        }
        if (selectedDocuments.isEmpty()) return null
        val currentTurn = interviewTurnRepository.findFirstBySession_IdAndUserAnswerIsNullOrderByTurnNoAsc(latestSession.id)
            ?: return null
        val queueSize = root["queue"]?.takeIf { it.isArray }?.size() ?: 0
        return ResumeInterviewSessionResponse(
            sessionId = latestSession.id,
            status = latestSession.status.name,
            mode = latestSession.mode.name,
            language = meta?.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: InterviewLanguage.KO.name,
            currentQuestion = toInterviewQuestionResponse(currentTurn),
            questionCount = meta?.get("questionCount")?.asInt() ?: max(queueSize, 1),
            difficulty = meta?.get("difficulty")?.asText(),
            difficultyRating = meta?.get("difficultyRating")?.asInt(),
            categoryId = meta?.get("categoryId")?.takeIf { it.canConvertToLong() }?.asLong(),
            categoryName = meta?.get("categoryName")?.asText()?.takeIf { it.isNotBlank() },
            jobName = meta?.get("jobName")?.asText()?.takeIf { it.isNotBlank() },
            selectedDocuments = selectedDocuments,
            questionSetId = meta?.get("questionSetId")?.takeIf { it.canConvertToLong() }?.asLong()
                ?: latestSession.questionSet?.id,
            includeSelfIntroduction = meta?.get("includeSelfIntroduction")?.asBoolean() == true,
            providerUsed = meta?.get("providerUsed")?.asText()?.takeIf { it.isNotBlank() },
            fallbackDepth = meta?.get("fallbackDepth")?.asInt() ?: 0
        )
    }

    @Transactional
    fun dismissMockSession(principal: AuthPrincipal, sessionId: Long) {
        val session = interviewSessionRepository.findByIdAndUser_Id(sessionId, principal.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "면접 세션을 찾을 수 없습니다.")
        if (session.mode != InterviewMode.DOC && session.mode != InterviewMode.MIXED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "실전 모의면접 세션만 이 경로에서 종료할 수 있습니다.")
        }
        if (session.status != InterviewStatus.IN_PROGRESS) return
        session.status = InterviewStatus.DONE
        session.finishedAt = OffsetDateTime.now()
    }

    private fun JsonNode.toInterviewHistoryDocumentResponse(): InterviewHistoryDocumentResponse? {
        val label = this["label"]?.asText()?.trim().orEmpty()
        if (label.isBlank()) return null
        return InterviewHistoryDocumentResponse(
            fileId = this["fileId"]?.takeIf { it.canConvertToLong() }?.asLong(),
            fileType = this["fileType"]?.asText(),
            label = label,
            ocrUsed = this["ocrUsed"]?.asBoolean() == true
        )
    }

    private fun retrievePromptSnippets(
        userId: Long,
        file: UserFile,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<String> {
        val retrievalQueries = buildRetrievalQueries(file.fileType, difficulty, questionCount)
        val allChunks = docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId, file.id)
        val seenChunkNos = linkedSetOf<Int>()
        val results = mutableListOf<String>()

        retrievalQueries.forEach { query ->
            val matches = runCatching {
                val queryEmbedding = embeddingProviderRouter.embedText(query)
                semanticRetrieve(allChunks, queryEmbedding.values)
            }.onFailure { ex ->
                logger.warn("document retrieval embedding failed fileId={} query='{}' reason={}", file.id, query.take(80), ex.message)
            }.getOrElse {
                lexicalRetrieve(allChunks, query)
            }

            matches.forEach { (chunkNo, chunkText) ->
                if (seenChunkNos.add(chunkNo)) {
                    results += chunkText
                }
            }
        }

        if (results.isNotEmpty()) {
            return results
                .map(::sanitizePromptSnippet)
                .filter(::isUsablePromptSnippet)
                .map { it.take(420) }
                .take(max(2, min(questionCount + 1, 5)))
        }

        val chunks = allChunks.map { it.chunkText }
        return fallbackPromptSnippets(chunks)
    }

    private fun semanticRetrieve(chunks: List<DocChunkEmbedding>, queryVector: List<Double>): List<Pair<Int, String>> {
        val limit = 2
        if (chunks.isEmpty() || queryVector.isEmpty()) return emptyList()
        return chunks
            .mapNotNull { chunk ->
                val chunkVector = parseVectorLiteral(chunk.embedding) ?: return@mapNotNull null
                val score = cosineSimilarity(queryVector, chunkVector)
                Triple(chunk.chunkNo, chunk.chunkText, score)
            }
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }

    private fun lexicalRetrieve(chunks: List<DocChunkEmbedding>, query: String): List<Pair<Int, String>> {
        val limit = 2
        val queryTokens = tokenizeForRetrieval(query)
        if (queryTokens.isEmpty()) {
            return chunks
                .take(limit)
                .map { it.chunkNo to it.chunkText }
        }

        return chunks
            .map { chunk ->
                val chunkTokens = tokenizeForRetrieval(chunk.chunkText)
                val overlap = queryTokens.intersect(chunkTokens).size
                val score = overlap * 10 - kotlin.math.abs(chunk.chunkText.length - 320) / 40
                Triple(chunk.chunkNo, chunk.chunkText, score)
            }
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }

    private fun tokenizeForRetrieval(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^0-9a-zA-Z가-힣.+#]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun parseVectorLiteral(raw: String): List<Double>? {
        val normalized = raw.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isBlank()) return null
        return runCatching {
            normalized.split(",").map { it.trim().toDouble() }
        }.getOrNull()
    }

    private fun cosineSimilarity(left: List<Double>, right: List<Double>): Double {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return Double.NEGATIVE_INFINITY
        return dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm))
    }

    private fun List<Double>.toVectorLiteral(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun buildRetrievalQueries(
        fileType: FileType,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<String> {
        val difficultyHint = when (difficulty) {
            QuestionDifficulty.HARD -> "의사결정 근거, 트레이드오프, 수치 성과"
            QuestionDifficulty.EASY -> "핵심 역할, 주요 경험, 기본 동기"
            else -> "역할, 이유, 결과, 학습"
        }

        val base = when (fileType) {
            FileType.RESUME -> listOf(
                "이력서에서 실제로 수행한 역할과 책임이 드러나는 경험",
                "이력서에서 성과나 개선 결과가 드러나는 경험",
                "이력서에서 협업, 문제 해결, 의사결정이 드러나는 경험"
            )
            FileType.PORTFOLIO -> listOf(
                "포트폴리오에서 프로젝트 역할, 기술 선택, 구현 책임이 드러나는 내용",
                "포트폴리오에서 문제 해결 과정과 기술적 트레이드오프가 드러나는 내용",
                "포트폴리오에서 결과, 회고, 개선 포인트가 드러나는 내용"
            )
            FileType.INTRODUCE -> listOf(
                "자기소개서에서 지원 동기와 직무 적합성이 드러나는 내용",
                "자기소개서에서 강점과 근거 경험이 드러나는 내용",
                "자기소개서에서 어려움 극복과 성장 과정이 드러나는 내용"
            )
            else -> listOf("문서에서 면접 질문으로 발전시킬 수 있는 핵심 경험")
        }

        return base
            .take(max(2, min(questionCount, 3)))
            .map { "$it, 중점: $difficultyHint" }
    }

    private fun fallbackPromptSnippets(chunks: List<String>): List<String> {
        val normalized = chunks
            .map(::sanitizePromptSnippet)
            .filter(::isUsablePromptSnippet)

        if (normalized.isEmpty()) {
            return chunks.take(1).map { it.take(500) }
        }

        val indexes = when {
            normalized.size <= 3 -> normalized.indices.toList()
            else -> listOf(0, normalized.size / 2, normalized.lastIndex)
        }.distinct()

        return indexes.map { normalized[it].take(500) }
    }

    private fun resolveTechCandidates(userId: Long, categoryId: Long, difficulty: QuestionDifficulty?) =
        questionRepository.findCandidatesForUser(
            userId = userId,
            setStatus = QuestionSetStatus.ACTIVE,
            globalVisibility = QuestionSetVisibility.GLOBAL,
            difficulty = difficulty,
            sourceTag = null
        ).let { questions ->
            val categoryIds = resolveCategoryIds(categoryId)
            val filtered = if (categoryIds.isEmpty()) questions else questions.filter { it.category.id in categoryIds }
            filtered.filter { isAcceptableTechQuestion(it) }
        }

    private fun resolveOrGenerateTechCandidates(
        actor: User,
        contexts: List<InterviewCategoryContextResolver.ResolvedCategoryContext>,
        difficulty: QuestionDifficulty?,
        requestedCount: Int,
        language: InterviewLanguage
    ): List<QaQuestion> {
        val distinctContexts = contexts.distinctBy { it.category.id }
        if (distinctContexts.isEmpty()) return emptyList()

        val requestedPerSkill = requestedTechCandidatesPerSkill(requestedCount, distinctContexts.size)
        val existing = distinctContexts
            .flatMap { context ->
                resolveTechCandidates(actor.id, context.category.id, difficulty)
                    .shuffled()
                    .take(requestedPerSkill)
            }
            .distinctBy { it.id }
        if (existing.size >= requestedCount) {
            return existing
        }

        val existingCounts = existing.groupingBy { it.category.id }.eachCount()
        val contextsToGenerate = distinctContexts.filter { context ->
            (existingCounts[context.category.id] ?: 0) < requestedPerSkill
        }
        if (contextsToGenerate.isEmpty()) {
            return existing
        }

        val generated = generateTechQuestionsForCategories(
            actor = actor,
            contexts = contextsToGenerate,
            difficulty = difficulty,
            requestedPerSkill = requestedPerSkill,
            language = language
        )
        return (existing + generated).distinctBy { it.id }
    }

    private fun generateTechQuestionsForCategories(
        actor: User,
        contexts: List<InterviewCategoryContextResolver.ResolvedCategoryContext>,
        difficulty: QuestionDifficulty?,
        requestedPerSkill: Int,
        language: InterviewLanguage
    ): List<QaQuestion> {
        val distinctContexts = contexts.distinctBy { it.category.id }
        if (distinctContexts.isEmpty()) return emptyList()
        val jobName = distinctContexts.first().jobName

        val generated = try {
            interviewAiOrchestrator.generateTechQuestionsBatch(
                jobName = jobName,
                skillNames = distinctContexts.map { it.skillName },
                difficulty = difficulty,
                questionCountPerSkill = requestedPerSkill,
                language = language
            )
        } catch (ex: GeminiTransientException) {
            throw toGeminiOverloadException(ex)
        } catch (ex: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, ex.message ?: "기술 질문 생성에 실패했습니다.")
        } catch (ex: ResponseStatusException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(
                "tech question batch generation error skillCount={} skills={} reason={}",
                distinctContexts.size,
                distinctContexts.joinToString(",") { it.skillName },
                ex.message
            )
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "기술 질문 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                ex
            )
        }
        if (generated.isEmpty()) return emptyList()

        val contextBySkillName = distinctContexts.associateBy { it.skillName.trim().lowercase() }
        val collected = mutableListOf<QaQuestion>()
        generated.forEach { item ->
            val context = contextBySkillName[item.skillName.trim().lowercase()] ?: return@forEach
            val category = context.category
            val skillName = context.skillName
            val autoSet = findOrCreateAutoGeneratedSet(
                actor = actor,
                jobName = context.jobName,
                skillName = skillName
            )
            val fingerprint = fingerprintFor(item.questionText, categoryKey(category), (difficulty ?: QuestionDifficulty.MEDIUM).name)
            val question = questionRepository.findByFingerprintAndDeletedAtIsNull(fingerprint)
                ?: questionRepository.save(
                    QaQuestion(
                        fingerprint = fingerprint,
                        questionText = item.questionText.trim(),
                        canonicalAnswer = item.canonicalAnswer?.trim(),
                        category = category,
                        jobName = context.jobName,
                        skillName = skillName,
                        difficulty = difficulty ?: QuestionDifficulty.MEDIUM,
                        sourceTag = QuestionSourceTag.SYSTEM,
                        tagsJson = objectMapper.writeValueAsString(item.tags.distinct()),
                        createdBy = actor
                    )
                )
            if (question.sourceTag != QuestionSourceTag.SYSTEM) {
                question.sourceTag = QuestionSourceTag.SYSTEM
            }
            if (!questionSetItemRepository.existsBySet_IdAndQuestion_Id(autoSet.id, question.id)) {
                val nextOrder = questionSetItemRepository.findMaxOrderNo(autoSet.id) + 1
                questionSetItemRepository.save(
                    QaQuestionSetItem(
                        set = autoSet,
                        question = question,
                        orderNo = nextOrder
                    )
                )
            }
            collected += question
        }
        return collected
    }

    private fun toGeminiOverloadException(ex: GeminiTransientException): ResponseStatusException {
        val status = if (ex.statusCode == 429) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.SERVICE_UNAVAILABLE
        val providerLabel = when (ex.provider) {
            AiProvider.BEDROCK -> "Bedrock"
            AiProvider.GEMINI -> "Gemini"
        }
        return ResponseStatusException(
            status,
            "$providerLabel API 과부하로 요청을 처리할 수 없습니다. 1분 후 다시 시도해 주세요.",
            ex
        )
    }

    private fun resolveRequestedTechContexts(
        request: StartMockInterviewRequest
    ): List<InterviewCategoryContextResolver.ResolvedCategoryContext> {
        val skillNames = request.skillNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val categoryIds = request.categoryIds.distinct()
        if (categoryIds.isNotEmpty()) {
            val categories = categoryIds.map { categoryId ->
                categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 categoryId 입니다: $categoryId")
            }
            categories.forEach { category ->
                if (!category.isActive) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다: ${category.name}")
                }
                if (category.depth != 2 || category.parent == null) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술 카테고리만 선택할 수 있습니다: ${category.name}")
                }
            }
            val branchNames = categories.map { it.parent?.parent?.name?.trim().orEmpty() }.distinctBy { it.lowercase() }
            if (branchNames.size > 1) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모의면접 시작 시 선택한 기술 카테고리는 같은 계열에 속해야 합니다.")
            }
            val selectedJobName = request.jobName?.trim().orEmpty()
            val jobNames = categories.map { it.parent?.name?.trim().orEmpty() }.distinctBy { it.lowercase() }
            val invalidJobName = jobNames.firstOrNull { jobName ->
                jobName.isNotBlank() &&
                    !jobName.equals(selectedJobName, ignoreCase = true) &&
                    !jobName.equals("공통", ignoreCase = true)
            }
            if (invalidJobName != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모의면접 시작 시 선택한 기술 카테고리는 선택 직무 또는 공통 직무에만 속해야 합니다.")
            }
            return categories.map { category ->
                InterviewCategoryContextResolver.ResolvedCategoryContext(
                    category = category,
                    branchName = category.parent?.parent?.name?.trim().orEmpty().ifBlank { "계열" },
                    jobName = category.parent?.name?.trim().orEmpty().ifBlank { request.jobName?.trim().orEmpty().ifBlank { "직무" } },
                    skillName = category.name.trim()
                )
            }
        }

        if (skillNames.isNotEmpty()) {
            val resolvedJobName = request.jobName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모의면접 시작 시 직무 정보가 필요합니다.")
            return skillNames.map { skillName ->
                categoryContextResolver.resolve(
                categoryId = null,
                jobName = resolvedJobName,
                skillName = skillName,
                requireIfMissing = false
                ) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 기술 이름입니다: $skillName")
            }
        }
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모의면접에는 기술 선택이 필요합니다.")
    }

    private fun requestedTechCandidatesPerSkill(requestedCount: Int, skillCount: Int): Int {
        if (skillCount <= 0) return 0
        val base = (requestedCount + skillCount - 1) / skillCount
        return (base + 1).coerceIn(3, 5)
    }

    private fun findOrCreateAutoGeneratedSet(
        actor: User,
        jobName: String,
        skillName: String
    ): QaQuestionSet {
        val autoSetTitle = "$jobName / $skillName"
        return questionSetRepository.findFirstByOwnerUser_IdAndOwnerTypeAndVisibilityAndJobNameAndSkillNameAndDescriptionAndDeletedAtIsNullOrderByCreatedAtDesc(
            userId = actor.id,
            ownerType = QuestionSetOwnerType.USER,
            visibility = QuestionSetVisibility.PRIVATE,
            jobName = jobName,
            skillName = skillName,
            description = AI_GENERATED_SET_DESCRIPTION
        ) ?: questionSetRepository.save(
            QaQuestionSet(
                ownerUser = actor,
                ownerType = QuestionSetOwnerType.USER,
                title = autoSetTitle,
                jobName = jobName,
                skillName = skillName,
                description = AI_GENERATED_SET_DESCRIPTION,
                visibility = QuestionSetVisibility.PRIVATE,
                status = QuestionSetStatus.ACTIVE
            )
        )
    }

    private fun isAcceptableTechQuestion(question: QaQuestion): Boolean {
        val normalized = question.questionText.replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 18) return false
        // NOTE:
        // 과도한 하드 필터를 비활성화한다.
        // 품질 보정은 생성 프롬프트/평가 단계에서 처리한다.
        // if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED)\\b").containsMatchIn(normalized)) return false
        // val focusTokens = question.category.name
        //     .lowercase()
        //     .split(Regex("[^a-z0-9가-힣]+"))
        //     .filter { it.length >= 2 }
        //     .toSet()
        // if (focusTokens.isNotEmpty()) {
        //     val lowered = normalized.lowercase()
        //     if (focusTokens.none { lowered.contains(it) }) return false
        // }
        return true
    }

    private fun resolveCategoryIds(categoryId: Long): Set<Long> {
        val category = categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 categoryId 입니다: $categoryId")
        if (!category.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다.")
        }
        val descendants = categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc("${category.path}/")
        return (listOf(category.id) + descendants.map { it.id }).toSet()
    }

    private fun isOcrDocument(userId: Long, fileId: Long): Boolean {
        val latestJob = documentIngestionJobRepository.findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(userId, fileId)
        return extractExtractionMethod(latestJob?.metadataJson) == "OCR_TESSERACT"
    }

    private fun difficultyToRating(difficulty: QuestionDifficulty?): Int? {
        return when (difficulty) {
            QuestionDifficulty.EASY -> 2
            QuestionDifficulty.MEDIUM -> 3
            QuestionDifficulty.HARD -> 5
            null -> null
        }
    }

    private fun createTurnFromRef(session: InterviewSession, ref: InterviewPracticeService.QuestionRef): InterviewTurn {
        val language = resolveInterviewLanguage(session.configJson)
        val turn = when (ref.kind) {
            InterviewQuestionKind.TECH -> {
                val question = questionRepository.findByIdAndDeletedAtIsNull(ref.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다: ${ref.id}")
                InterviewTurn(
                    session = session,
                    turnNo = 1,
                    sourceTag = if (questionSetItemRepository.existsInAiGeneratedSetByQuestionId(question.id)) {
                        TurnSourceTag.SYSTEM
                    } else {
                        when (question.sourceTag) {
                            QuestionSourceTag.SYSTEM -> TurnSourceTag.SYSTEM
                            QuestionSourceTag.USER -> TurnSourceTag.USER
                        }
                    },
                    question = question,
                    questionTextSnapshot = interviewAiOrchestrator.localizeInterviewText(
                        question.questionText,
                        language,
                        "interview question"
                    ) ?: question.questionText,
                    categorySnapshot = question.category.name,
                    jobSnapshot = question.jobName ?: question.category.parent?.name?.trim(),
                    skillSnapshot = question.skillName ?: question.category.name.trim(),
                    category = question.category,
                    difficulty = question.difficulty.name,
                    tagsJson = question.tagsJson
                )
            }

            InterviewQuestionKind.DOCUMENT -> {
                val question = documentQuestionRepository.findByIdAndUserId(ref.id, session.user.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문서 질문을 찾을 수 없습니다: ${ref.id}")
                InterviewTurn(
                    session = session,
                    turnNo = 1,
                    sourceTag = TurnSourceTag.DOC_RAG,
                    documentQuestion = question,
                    questionTextSnapshot = interviewAiOrchestrator.localizeInterviewText(
                        question.questionText,
                        language,
                        "interview question"
                    ) ?: question.questionText,
                    categorySnapshot = question.questionType,
                    difficulty = question.difficulty,
                    tagsJson = "[]",
                    ragContextJson = question.evidenceJson
                )
            }

            InterviewQuestionKind.INTRO -> {
                InterviewTurn(
                    session = session,
                    turnNo = 1,
                    sourceTag = TurnSourceTag.DOC_RAG,
                    questionTextSnapshot = interviewAiOrchestrator.localizedIntroQuestion(language),
                    categorySnapshot = INTRO_CATEGORY,
                    tagsJson = "[]"
                )
            }
        }
        return interviewTurnRepository.save(turn)
    }

    private fun toInterviewQuestionResponse(turn: InterviewTurn): InterviewQuestionResponse {
        val isIntroductionTurn = isIntroductionTurn(turn)
        return InterviewQuestionResponse(
            turnId = turn.id,
            turnNo = turn.turnNo,
            questionId = turn.question?.id,
            documentQuestionId = turn.documentQuestion?.id,
            questionKind = when {
                isIntroductionTurn -> InterviewQuestionKind.INTRO
                turn.question != null -> InterviewQuestionKind.TECH
                else -> InterviewQuestionKind.DOCUMENT
            },
            categoryId = turn.category?.id,
            questionText = turn.questionTextSnapshot,
            sourceTag = if (isIntroductionTurn) TurnSourceTag.INTRO else turn.sourceTag,
            category = turn.categorySnapshot,
            difficulty = turn.difficulty,
            tags = runCatching { objectMapper.readValue(turn.tagsJson, Array<String>::class.java).toList() }.getOrDefault(emptyList())
        )
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

    private fun resolveInterviewLanguage(configJson: String?): InterviewLanguage {
        if (configJson.isNullOrBlank()) return InterviewLanguage.KO
        val root = runCatching { objectMapper.readTree(configJson) }.getOrNull() ?: return InterviewLanguage.KO
        val raw = root.path("meta").path("language").asText().trim().uppercase()
        return runCatching { InterviewLanguage.valueOf(raw) }.getOrDefault(InterviewLanguage.KO)
    }

    private fun extractPdfText(file: UserFile): ExtractedDocumentText {
        if (s3Properties.bucket.isBlank()) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 버킷 설정이 누락되었습니다.")
        }
        val key = file.storageKey.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문서 저장 경로를 찾을 수 없습니다.")
        val request = GetObjectRequest.builder()
            .bucket(s3Properties.bucket.trim())
            .key(key)
            .build()
        val bytes = runCatching { s3Client.getObjectAsBytes(request).asByteArray() }
            .getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "문서 파일을 불러오지 못했습니다.")
            }

        return PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            val pdfText = normalizeText(PDFTextStripper().getText(document))
            if (!ocrProperties.enabled || pdfText.length >= ocrProperties.fallbackMinTextLength) {
                return@use ExtractedDocumentText(
                    text = pdfText,
                    method = "PDFBOX",
                    ocrLanguages = null
                )
            }

            val ocrResult = extractPdfTextWithOcr(document)
            val finalText = if (ocrResult.text.isNotBlank()) ocrResult.text else pdfText
            ExtractedDocumentText(
                text = finalText,
                method = if (ocrResult.text.isNotBlank()) "OCR_TESSERACT" else "PDFBOX",
                ocrLanguages = ocrResult.languages
            )
        }
    }

    private fun sanitizePromptSnippet(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun isUsablePromptSnippet(text: String): Boolean {
        if (text.length < 40) return false
        val lettersOnly = text.filter { it.isLetter() }
        if (lettersOnly.isEmpty()) return false
        val uppercaseRatio = lettersOnly.count { it.isUpperCase() }.toDouble() / lettersOnly.length.toDouble()
        val longTokens = text.split(" ").count { it.length >= 4 }
        val weirdTokenCount = text.split(" ").count { token ->
            token.length >= 6 && token.count(Char::isUpperCase) >= 4
        }
        return uppercaseRatio < 0.72 && longTokens >= 4 && weirdTokenCount <= 3
    }

    private fun fingerprintFor(questionText: String, categoryPath: String, difficulty: String): String {
        val normalized = "${questionText.trim()}|$categoryPath|$difficulty"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun categoryKey(category: com.cw.vlainter.domain.interview.entity.QaCategory): String {
        val job = category.parent?.name?.trim().orEmpty()
        return "$job/${category.name.trim()}".trim()
    }

    private fun FileType.toPromptLabel(): String = when (this) {
        FileType.RESUME -> "이력서"
        FileType.INTRODUCE -> "자기소개서"
        FileType.PORTFOLIO -> "포트폴리오"
        FileType.PROFILE_IMAGE -> "프로필 이미지"
    }

    private fun splitIntoChunks(text: String, maxChars: Int = 1200, overlapChars: Int = 200): List<String> {
        if (text.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = min(text.length, start + maxChars)
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                chunks += chunk
            }
            if (end == text.length) break
            start = max(0, end - overlapChars)
        }
        return chunks
    }

    private fun estimateTokenCount(text: String): Int = max(1, text.length / 4)

    private fun distribute(total: Int, bucketCount: Int): List<Int> {
        if (bucketCount <= 0) return emptyList()
        val base = total / bucketCount
        val remainder = total % bucketCount
        return List(bucketCount) { index -> base + if (index < remainder) 1 else 0 }
    }

    private fun loadActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 계정은 문서 면접 기능을 사용할 수 없습니다.")
        }
        return user
    }

    private fun loadOwnedInterviewDocument(userId: Long, fileId: Long): UserFile {
        val file = userFileRepository.findByIdAndUser_IdAndDeletedAtIsNull(fileId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다.")
        if (!file.fileType.isInterviewDocument()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "문서 기반 면접에는 이력서/자기소개서/포트폴리오만 사용할 수 있습니다.")
        }
        return file
    }

    private fun loadOwnedUserQuestionSet(userId: Long, setId: Long): QaQuestionSet {
        val set = questionSetRepository.findByIdAndDeletedAtIsNull(setId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문 세트를 찾을 수 없습니다.")
        if (set.ownerUser?.id != userId || set.ownerType != QuestionSetOwnerType.USER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "내 질문 세트만 실전 모의면접에 사용할 수 있습니다.")
        }
        if (set.status != QuestionSetStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "비활성 질문 세트는 사용할 수 없습니다.")
        }
        return set
    }

    private fun toIngestionResponse(job: DocumentIngestionJob): DocumentIngestionResponse {
        return DocumentIngestionResponse(
            jobId = job.id,
            fileId = job.documentFileId,
            status = job.status,
            chunkCount = job.chunkCount,
            extractionMethod = extractExtractionMethod(job.metadataJson),
            ocrUsed = extractExtractionMethod(job.metadataJson) == "OCR_TESSERACT",
            requestedAt = job.requestedAt,
            finishedAt = job.finishedAt,
            errorMessage = job.errorMessage
        )
    }

    private fun extractExtractionMethod(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            objectMapper.readTree(rawJson)
                .path("extractionMethod")
                .takeIf { !it.isMissingNode && !it.isNull }
                ?.asText()
                ?.trim()
        }.getOrNull().takeIf { !it.isNullOrBlank() }
    }

    private fun FileType.isInterviewDocument(): Boolean {
        return this == FileType.RESUME || this == FileType.INTRODUCE || this == FileType.PORTFOLIO
    }

    private fun extractPdfTextWithOcr(document: PDDocument): OcrExtractionResult {
        val languages = resolveOcrLanguages() ?: return OcrExtractionResult("", null)
        val renderer = PDFRenderer(document)
        val pageTexts = mutableListOf<String>()

        for (pageIndex in 0 until document.numberOfPages) {
            val imagePath = Files.createTempFile("vlainter-ocr-$pageIndex-", ".png")
            val outputBase = imagePath.resolveSibling(imagePath.fileName.toString().removeSuffix(".png"))
            try {
                val image = renderer.renderImageWithDPI(pageIndex, ocrProperties.renderDpi)
                imagePath.toFile().outputStream().use { output ->
                    javax.imageio.ImageIO.write(image, "png", output)
                }
                val text = runTesseract(imagePath, outputBase, languages)
                if (text.isNotBlank()) {
                    pageTexts += text
                }
            } catch (ex: Exception) {
                logger.warn("ocr fallback failed page={} reason={}", pageIndex + 1, ex.message)
            } finally {
                Files.deleteIfExists(imagePath)
                Files.deleteIfExists(outputBase.resolveSibling("${outputBase.fileName}.txt"))
            }
        }

        return OcrExtractionResult(
            text = normalizeText(pageTexts.joinToString(" ")),
            languages = languages
        )
    }

    private fun runTesseract(imagePath: Path, outputBase: Path, languages: String): String {
        val process = ProcessBuilder(
            ocrProperties.tesseractCommand,
            imagePath.toString(),
            outputBase.toString(),
            "-l",
            languages,
            "--psm",
            "6",
            "txt"
        )
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(ocrProperties.timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("tesseract timeout")
        }

        val commandOutput = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            throw IllegalStateException("tesseract exit=${process.exitValue()} output=${commandOutput.take(300)}")
        }

        val outputTextFile = outputBase.resolveSibling("${outputBase.fileName}.txt")
        if (!Files.exists(outputTextFile)) {
            return ""
        }
        return Files.readString(outputTextFile)
    }

    private fun resolveOcrLanguages(): String? {
        val configured = ocrProperties.languages.split("+")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val available = availableTesseractLanguages ?: runCatching {
            ProcessBuilder(ocrProperties.tesseractCommand, "--list-langs")
                .redirectErrorStream(true)
                .start()
                .also { process ->
                    if (!process.waitFor(ocrProperties.timeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error("tesseract language listing timeout")
                    }
                }
                .inputStream
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .drop(1)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
        }.onFailure {
            logger.warn("tesseract language detection failed reason={}", it.message)
        }.getOrNull()?.also { detected ->
            availableTesseractLanguages = detected
        } ?: return null

        if (available.isEmpty()) return null

        val matchedConfigured = configured.filter { it in available }
        if (matchedConfigured.isNotEmpty()) {
            return matchedConfigured.joinToString("+")
        }

        val fallback = available.filter { it != "osd" }
        return fallback.takeIf { it.isNotEmpty() }?.joinToString("+")
    }

    private fun runAfterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                action()
            }
        })
    }

    private fun normalizeText(text: String): String {
        return text.replace("\u0000", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class ExtractedDocumentText(
        val text: String,
        val method: String,
        val ocrLanguages: String?
    )

    private data class OcrExtractionResult(
        val text: String,
        val languages: String?
    )
}
