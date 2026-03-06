package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.EmbeddingProviderRouter
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.dto.DocumentIngestionResponse
import com.cw.vlainter.domain.interview.dto.InterviewQuestionResponse
import com.cw.vlainter.domain.interview.dto.ReadyDocumentResponse
import com.cw.vlainter.domain.interview.dto.StartMockInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.entity.DocChunkEmbedding
import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.interview.entity.DocumentQuestion
import com.cw.vlainter.domain.interview.entity.DocumentQuestionSet
import com.cw.vlainter.domain.interview.entity.InterviewMode
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.cw.vlainter.domain.interview.entity.InterviewSession
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
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
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.config.properties.S3Properties
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.math.min

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
    private val interviewSessionRepository: InterviewSessionRepository,
    private val interviewTurnRepository: InterviewTurnRepository,
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val embeddingProviderRouter: EmbeddingProviderRouter,
    private val objectMapper: ObjectMapper,
    private val s3Client: S3Client,
    private val s3Properties: S3Properties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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
                    lastIngestedAt = latestJob.finishedAt
                )
            }
            .toList()
    }

    @Transactional
    fun ingestDocument(principal: AuthPrincipal, fileId: Long): DocumentIngestionResponse {
        val actor = loadActiveUser(principal.userId)
        val file = loadOwnedInterviewDocument(actor.id, fileId)

        val job = documentIngestionJobRepository.save(
            DocumentIngestionJob(
                userId = actor.id,
                documentFileId = file.id,
                status = DocumentIngestionStatus.QUEUED
            )
        )

        try {
            job.status = DocumentIngestionStatus.PROCESSING
            job.startedAt = OffsetDateTime.now()
            documentIngestionJobRepository.save(job)

            val text = extractPdfText(file)
            val chunks = splitIntoChunks(text)
            if (chunks.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "문서에서 분석 가능한 텍스트를 찾지 못했습니다.")
            }

            val embeddings = chunks.mapIndexed { index, chunk ->
                val embedded = embeddingProviderRouter.embedText(chunk)
                DocChunkEmbedding(
                    userFileId = file.id,
                    userId = actor.id,
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

            docChunkEmbeddingRepository.deleteAllByUserIdAndUserFileId(actor.id, file.id)
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
                    "preview" to text.take(500)
                )
            )
            job.finishedAt = OffsetDateTime.now()
            documentIngestionJobRepository.save(job)
        } catch (ex: Exception) {
            logger.warn("document ingestion failed fileId={} reason={}", fileId, ex.message)
            job.status = DocumentIngestionStatus.FAILED
            job.errorMessage = ex.message?.take(1000)
            job.finishedAt = OffsetDateTime.now()
            documentIngestionJobRepository.save(job)
            if (ex is ResponseStatusException) throw ex
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "문서 분석에 실패했습니다.")
        }

        return toIngestionResponse(job)
    }

    @Transactional
    fun startMockInterview(principal: AuthPrincipal, request: StartMockInterviewRequest): StartTechInterviewResponse {
        val actor = loadActiveUser(principal.userId)
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

        val requestedCount = request.questionCount
        val techCandidates = if (request.categoryId != null) {
            resolveTechCandidates(actor.id, request.categoryId, request.difficulty)
        } else {
            emptyList()
        }

        val techTarget = if (techCandidates.isNotEmpty() && requestedCount > 1) {
            min(techCandidates.size, max(1, requestedCount / 3))
        } else {
            0
        }
        val documentTarget = max(1, requestedCount - techTarget)

        val generatedQuestions = generateDocumentQuestions(actor, files, request.difficulty, documentTarget)
        if (generatedQuestions.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "문서 기반 질문 생성에 실패했습니다.")
        }

        val selectedTech = techCandidates.shuffled().take(techTarget)
        val queue = buildList {
            addAll(generatedQuestions.map { InterviewPracticeService.QuestionRef(InterviewQuestionKind.DOCUMENT, it.id) })
            addAll(selectedTech.map { InterviewPracticeService.QuestionRef(InterviewQuestionKind.TECH, it.id) })
        }.shuffled()

        val session = interviewSessionRepository.save(
            InterviewSession(
                user = actor,
                mode = if (selectedTech.isEmpty()) InterviewMode.DOC else InterviewMode.MIXED,
                status = InterviewStatus.IN_PROGRESS,
                revealPolicy = RevealPolicy.END_ONLY,
                configJson = objectMapper.writeValueAsString(mapOf("queue" to queue, "cursor" to 1))
            )
        )

        val firstTurn = createTurnFromRef(session, 1, queue.first())
        return StartTechInterviewResponse(
            sessionId = session.id,
            status = session.status.name,
            currentQuestion = toInterviewQuestionResponse(firstTurn),
            hasNext = queue.size > 1
        )
    }

    private fun generateDocumentQuestions(
        actor: User,
        files: List<UserFile>,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<DocumentQuestion> {
        val allocation = distribute(questionCount, files.size)
        val results = mutableListOf<DocumentQuestion>()

        files.forEachIndexed { index, file ->
            val targetCount = allocation[index]
            if (targetCount <= 0) return@forEachIndexed

            val chunks = docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(actor.id, file.id)
                .map { it.chunkText }
            if (chunks.isEmpty()) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "문서 분석 결과가 없습니다: ${file.originalFileName}")
            }

            val snippets = chunks.take(6).map { it.take(1200) }
            val generated = interviewAiOrchestrator.generateDocumentQuestions(
                fileTypeLabel = file.fileType.name,
                difficulty = difficulty,
                questionCount = targetCount,
                contextSnippets = snippets
            )

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
                            "generatedAt" to OffsetDateTime.now().toString()
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

        return results.take(questionCount)
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
            if (categoryIds.isEmpty()) questions else questions.filter { it.category.id in categoryIds }
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

    private fun createTurnFromRef(session: InterviewSession, turnNo: Int, ref: InterviewPracticeService.QuestionRef): InterviewTurn {
        val turn = when (ref.kind) {
            InterviewQuestionKind.TECH -> {
                val question = questionRepository.findByIdAndDeletedAtIsNull(ref.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다: ${ref.id}")
                InterviewTurn(
                    session = session,
                    turnNo = turnNo,
                    sourceTag = when (question.sourceTag.name) {
                        "SYSTEM" -> TurnSourceTag.SYSTEM
                        else -> TurnSourceTag.USER
                    },
                    question = question,
                    questionTextSnapshot = question.questionText,
                    categorySnapshot = question.category.name,
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
                    turnNo = turnNo,
                    sourceTag = TurnSourceTag.DOC_RAG,
                    documentQuestion = question,
                    questionTextSnapshot = question.questionText,
                    categorySnapshot = question.questionType,
                    difficulty = question.difficulty,
                    tagsJson = objectMapper.writeValueAsString(listOf(question.questionType)),
                    ragContextJson = question.evidenceJson
                )
            }
        }
        return interviewTurnRepository.save(turn)
    }

    private fun toInterviewQuestionResponse(turn: InterviewTurn): InterviewQuestionResponse {
        return InterviewQuestionResponse(
            turnId = turn.id,
            turnNo = turn.turnNo,
            questionId = turn.question?.id,
            documentQuestionId = turn.documentQuestion?.id,
            questionKind = if (turn.question != null) InterviewQuestionKind.TECH else InterviewQuestionKind.DOCUMENT,
            categoryId = turn.category?.id,
            questionText = turn.questionTextSnapshot,
            sourceTag = turn.sourceTag,
            category = turn.categorySnapshot,
            difficulty = turn.difficulty,
            tags = runCatching { objectMapper.readValue(turn.tagsJson, Array<String>::class.java).toList() }.getOrDefault(emptyList())
        )
    }

    private fun extractPdfText(file: UserFile): String {
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

        val text = PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            PDFTextStripper().getText(document)
        }
        return text.replace("\u0000", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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

    private fun toIngestionResponse(job: DocumentIngestionJob): DocumentIngestionResponse {
        return DocumentIngestionResponse(
            jobId = job.id,
            fileId = job.documentFileId,
            status = job.status,
            chunkCount = job.chunkCount,
            requestedAt = job.requestedAt,
            finishedAt = job.finishedAt,
            errorMessage = job.errorMessage
        )
    }

    private fun List<Double>.toVectorLiteral(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun FileType.isInterviewDocument(): Boolean {
        return this == FileType.RESUME || this == FileType.INTRODUCE || this == FileType.PORTFOLIO
    }
}
