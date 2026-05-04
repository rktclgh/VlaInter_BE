@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.ai.AiRoutingContextHolder
import com.cw.vlainter.domain.interview.ai.EmbeddingProviderRouter
import com.cw.vlainter.domain.interview.dto.ReadyDocumentResponse
import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.interview.repository.DocumentQuestionRepository
import com.cw.vlainter.domain.interview.repository.DocumentQuestionSetRepository
import com.cw.vlainter.domain.interview.repository.InterviewSessionRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnEvaluationRepository
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.interview.repository.SavedQuestionRepository
import com.cw.vlainter.domain.interview.repository.UserQuestionAttemptRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.config.properties.AiProperties
import com.cw.vlainter.global.config.properties.OcrProperties
import com.cw.vlainter.global.config.properties.S3Properties
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.beans.factory.ObjectProvider
import java.time.OffsetDateTime
import java.util.Optional
import software.amazon.awssdk.services.s3.S3Client

@ExtendWith(MockitoExtension::class)
class DocumentInterviewServiceTests {
    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var userFileRepository: UserFileRepository
    @Mock private lateinit var documentIngestionJobRepository: DocumentIngestionJobRepository
    @Mock private lateinit var docChunkEmbeddingRepository: DocChunkEmbeddingRepository
    @Mock private lateinit var documentQuestionSetRepository: DocumentQuestionSetRepository
    @Mock private lateinit var documentQuestionRepository: DocumentQuestionRepository
    @Mock private lateinit var questionRepository: QaQuestionRepository
    @Mock private lateinit var categoryRepository: QaCategoryRepository
    @Mock private lateinit var categoryContextResolver: InterviewCategoryContextResolver
    @Mock private lateinit var jobSkillCatalogService: JobSkillCatalogService
    @Mock private lateinit var questionSetRepository: QaQuestionSetRepository
    @Mock private lateinit var questionSetItemRepository: QaQuestionSetItemRepository
    @Mock private lateinit var interviewSessionRepository: InterviewSessionRepository
    @Mock private lateinit var interviewTurnRepository: InterviewTurnRepository
    @Mock private lateinit var interviewTurnEvaluationRepository: InterviewTurnEvaluationRepository
    @Mock private lateinit var userQuestionAttemptRepository: UserQuestionAttemptRepository
    @Mock private lateinit var savedQuestionRepository: SavedQuestionRepository
    @Mock private lateinit var studentCourseMaterialRepository: com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
    @Mock private lateinit var studentCourseMaterialVisualAssetRepository: com.cw.vlainter.domain.student.repository.StudentCourseMaterialVisualAssetRepository
    @Mock private lateinit var interviewAiOrchestrator: com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
    @Mock private lateinit var s3Client: S3Client
    @Mock private lateinit var selfProvider: ObjectProvider<DocumentInterviewService>
    @Mock private lateinit var userGeminiApiKeyService: UserGeminiApiKeyService

    private val objectMapper = ObjectMapper()
    private val aiRoutingContextHolder = AiRoutingContextHolder()
    private val embeddingProviderRouter = EmbeddingProviderRouter(AiProperties(), emptyList())
    private val s3Properties = S3Properties()
    private val ocrProperties = OcrProperties(enabled = false)

    @Test
    fun `getReadyDocuments는 문서별 최신 ingestion job을 한 번에 조회한다`() {
        val user = createUser()
        val now = OffsetDateTime.now()
        val resume = createFile(
            id = 101L,
            user = user,
            fileType = FileType.RESUME,
            originalFileName = "resume.pdf",
            createdAt = now
        )
        val portfolio = createFile(
            id = 102L,
            user = user,
            fileType = FileType.PORTFOLIO,
            originalFileName = "portfolio.pdf",
            createdAt = now.minusMinutes(1)
        )
        val profileImage = createFile(
            id = 103L,
            user = user,
            fileType = FileType.PROFILE_IMAGE,
            originalFileName = "profile.png",
            createdAt = now.minusMinutes(2)
        )
        val resumeReadyJob = createJob(
            id = 201L,
            userId = user.id,
            documentFileId = resume.id,
            status = DocumentIngestionStatus.READY,
            metadataJson = """{"extractionMethod":"OCR_TESSERACT"}""",
            chunkCount = 12,
            requestedAt = now.minusMinutes(5),
            finishedAt = now.minusMinutes(1)
        )
        val resumeOlderJob = createJob(
            id = 202L,
            userId = user.id,
            documentFileId = resume.id,
            status = DocumentIngestionStatus.QUEUED,
            requestedAt = now.minusMinutes(10)
        )
        val portfolioProcessingJob = createJob(
            id = 203L,
            userId = user.id,
            documentFileId = portfolio.id,
            status = DocumentIngestionStatus.PROCESSING,
            requestedAt = now.minusMinutes(2)
        )
        val portfolioOlderReadyJob = createJob(
            id = 204L,
            userId = user.id,
            documentFileId = portfolio.id,
            status = DocumentIngestionStatus.READY,
            requestedAt = now.minusMinutes(8),
            finishedAt = now.minusMinutes(7)
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(userFileRepository.findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(user.id))
            .willReturn(listOf(resume, portfolio, profileImage))
        given(
            documentIngestionJobRepository.findAllByUserIdAndDocumentFileIdInOrderByDocumentFileIdAscRequestedAtDesc(
                user.id,
                listOf(resume.id, portfolio.id)
            )
        ).willReturn(listOf(resumeReadyJob, resumeOlderJob, portfolioProcessingJob, portfolioOlderReadyJob))

        val result = service().getReadyDocuments(AuthPrincipal(user.id, user.email, user.name, user.role))

        assertThat(result).containsExactly(
            ReadyDocumentResponse(
                fileId = resume.id,
                fileName = resume.originalFileName,
                fileType = resume.fileType.name,
                status = DocumentIngestionStatus.READY,
                chunkCount = 12,
                extractionMethod = "OCR_TESSERACT",
                ocrUsed = true,
                lastIngestedAt = resumeReadyJob.finishedAt
            )
        )
        then(documentIngestionJobRepository).should().findAllByUserIdAndDocumentFileIdInOrderByDocumentFileIdAscRequestedAtDesc(
            user.id,
            listOf(resume.id, portfolio.id)
        )
        then(documentIngestionJobRepository).should(never()).findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(user.id, resume.id)
    }

    private fun service() = DocumentInterviewService(
        userRepository = userRepository,
        userFileRepository = userFileRepository,
        documentIngestionJobRepository = documentIngestionJobRepository,
        docChunkEmbeddingRepository = docChunkEmbeddingRepository,
        documentQuestionSetRepository = documentQuestionSetRepository,
        documentQuestionRepository = documentQuestionRepository,
        questionRepository = questionRepository,
        categoryRepository = categoryRepository,
        categoryContextResolver = categoryContextResolver,
        jobSkillCatalogService = jobSkillCatalogService,
        questionSetRepository = questionSetRepository,
        questionSetItemRepository = questionSetItemRepository,
        interviewSessionRepository = interviewSessionRepository,
        interviewTurnRepository = interviewTurnRepository,
        interviewTurnEvaluationRepository = interviewTurnEvaluationRepository,
        userQuestionAttemptRepository = userQuestionAttemptRepository,
        savedQuestionRepository = savedQuestionRepository,
        studentCourseMaterialRepository = studentCourseMaterialRepository,
        studentCourseMaterialVisualAssetRepository = studentCourseMaterialVisualAssetRepository,
        interviewAiOrchestrator = interviewAiOrchestrator,
        aiRoutingContextHolder = aiRoutingContextHolder,
        embeddingProviderRouter = embeddingProviderRouter,
        objectMapper = objectMapper,
        s3Client = s3Client,
        s3Properties = s3Properties,
        ocrProperties = ocrProperties,
        selfProvider = selfProvider,
        userGeminiApiKeyService = userGeminiApiKeyService
    )

    private fun createUser(id: Long = 1L) = User(
        id = id,
        email = "user@vlainter.com",
        password = "encoded",
        name = "테스터",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        geminiApiKeyEncrypted = "encrypted"
    )

    private fun createFile(
        id: Long,
        user: User,
        fileType: FileType,
        originalFileName: String,
        createdAt: OffsetDateTime
    ) = UserFile(
        id = id,
        user = user,
        fileType = fileType,
        fileUrl = "https://example.com/$id",
        fileName = originalFileName,
        originalFileName = originalFileName,
        storageFileName = "storage-$id",
        storageKey = "uploads/$id",
        contentType = "application/pdf",
        fileSizeBytes = 1024,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun createJob(
        id: Long,
        userId: Long,
        documentFileId: Long,
        status: DocumentIngestionStatus,
        metadataJson: String = "{}",
        chunkCount: Int? = null,
        requestedAt: OffsetDateTime? = null,
        finishedAt: OffsetDateTime? = null
    ) = DocumentIngestionJob(
        id = id,
        userId = userId,
        documentFileId = documentFileId,
        status = status,
        metadataJson = metadataJson,
        chunkCount = chunkCount,
        requestedAt = requestedAt,
        finishedAt = finishedAt
    )
}
