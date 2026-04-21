@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.student.service

import com.cw.vlainter.domain.interview.ai.CourseMaterialSummarySource
import com.cw.vlainter.domain.interview.ai.EmbeddingGenerationResult
import com.cw.vlainter.domain.interview.ai.EmbeddingProviderRouter
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.entity.DocChunkEmbedding
import com.cw.vlainter.domain.interview.repository.ChunkSnippetProjection
import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.student.entity.StudentCourse
import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialVisualAssetRepository
import com.cw.vlainter.domain.student.repository.StudentCourseRepository
import com.cw.vlainter.domain.student.repository.StudentCourseYoutubeSummaryJobRepository
import com.cw.vlainter.domain.student.repository.StudentExamQuestionRepository
import com.cw.vlainter.domain.student.repository.StudentExamSessionRepository
import com.cw.vlainter.domain.student.repository.StudentWrongAnswerItemRepository
import com.cw.vlainter.domain.student.repository.StudentWrongAnswerSetRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.service.UserFileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.beans.factory.ObjectProvider
import java.time.OffsetDateTime

@ExtendWith(MockitoExtension::class)
class StudentCourseServiceTests {
    @org.mockito.Mock private lateinit var studentCourseRepository: StudentCourseRepository
    @org.mockito.Mock private lateinit var studentCourseMaterialRepository: StudentCourseMaterialRepository
    @org.mockito.Mock private lateinit var studentExamSessionRepository: StudentExamSessionRepository
    @org.mockito.Mock private lateinit var studentExamQuestionRepository: StudentExamQuestionRepository
    @org.mockito.Mock private lateinit var studentWrongAnswerSetRepository: StudentWrongAnswerSetRepository
    @org.mockito.Mock private lateinit var studentWrongAnswerItemRepository: StudentWrongAnswerItemRepository
    @org.mockito.Mock private lateinit var studentCourseMaterialVisualAssetRepository: StudentCourseMaterialVisualAssetRepository
    @org.mockito.Mock private lateinit var studentCourseYoutubeSummaryJobRepository: StudentCourseYoutubeSummaryJobRepository
    @org.mockito.Mock private lateinit var documentIngestionJobRepository: DocumentIngestionJobRepository
    @org.mockito.Mock private lateinit var docChunkEmbeddingRepository: DocChunkEmbeddingRepository
    @org.mockito.Mock private lateinit var embeddingProviderRouter: EmbeddingProviderRouter
    @org.mockito.Mock private lateinit var documentInterviewService: com.cw.vlainter.domain.interview.service.DocumentInterviewService
    @org.mockito.Mock private lateinit var interviewAiOrchestrator: InterviewAiOrchestrator
    @org.mockito.Mock private lateinit var youTubeTranscriptService: YouTubeTranscriptService
    @org.mockito.Mock private lateinit var userRepository: UserRepository
    @org.mockito.Mock private lateinit var userGeminiApiKeyService: UserGeminiApiKeyService
    @org.mockito.Mock private lateinit var userFileService: UserFileService
    @org.mockito.Mock private lateinit var selfProvider: ObjectProvider<StudentCourseService>

    @Test
    fun `collectSummarySources는 semantic chunk가 unusable이면 전체 chunk fallback을 사용한다`() {
        val course = createCourse()
        val material = createMaterial(course)
        given(embeddingProviderRouter.embedText(anyString()))
            .willReturn(EmbeddingGenerationResult(model = "mock", values = listOf(0.1, 0.2)))
        given(docChunkEmbeddingRepository.findTopSemanticMatches(anyLong(), anyLong(), anyString(), anyInt()))
            .willReturn(listOf(chunkProjection(1, "짧은 요약 텍스트")))
        given(docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id))
            .willReturn(
                listOf(
                    chunkEntity(material.userFile.id, 1, "이 자료는 자료구조의 핵심 개념과 배열, 연결 리스트, 스택, 큐의 차이를 길게 설명하는 강의 노트입니다. 학생이 요약을 만들 수 있을 만큼 충분히 긴 문장으로 구성되어 있습니다.")
                )
            )

        val result = invokeCollectSummarySources(service(), 1L, course, listOf(material))

        assertThat(result).hasSize(1)
        assertThat(result.first().snippets).isNotEmpty()
        then(docChunkEmbeddingRepository).should().findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id)
    }

    @Test
    fun `collectMaterialSnippets는 semantic chunk가 unusable이면 전체 chunk fallback을 사용한다`() {
        val course = createCourse()
        val material = createMaterial(course)
        given(embeddingProviderRouter.embedText(anyString()))
            .willReturn(EmbeddingGenerationResult(model = "mock", values = listOf(0.1, 0.2)))
        given(docChunkEmbeddingRepository.findTopSemanticMatches(anyLong(), anyLong(), anyString(), anyInt()))
            .willReturn(listOf(chunkProjection(1, "짧은 시험 텍스트")))
        given(docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id))
            .willReturn(
                listOf(
                    chunkEntity(material.userFile.id, 1, "시험 대비를 위해 트리 순회, 그래프 탐색, 해시 충돌 처리와 같은 내용을 충분히 길게 설명한 강의자료입니다. 문제 생성에 쓸 수 있도록 80자를 훌쩍 넘는 텍스트입니다."),
                    chunkEntity(material.userFile.id, 2, "추가로 시간 복잡도와 공간 복잡도를 비교하는 내용도 포함되어 있어 서술형 문제를 만들 수 있을 정도로 맥락이 풍부합니다.")
                )
            )

        val result = invokeCollectMaterialSnippets(service(), 1L, course, listOf(material), 6)

        assertThat(result).isNotEmpty()
        then(docChunkEmbeddingRepository).should().findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id)
    }

    @Test
    fun `collectStyleReferenceSnippets는 semantic chunk가 unusable이면 전체 chunk fallback을 사용한다`() {
        val course = createCourse()
        val material = createMaterial(course)
        given(embeddingProviderRouter.embedText(anyString()))
            .willReturn(EmbeddingGenerationResult(model = "mock", values = listOf(0.1, 0.2)))
        given(docChunkEmbeddingRepository.findTopSemanticMatches(anyLong(), anyLong(), anyString(), anyInt()))
            .willReturn(listOf(chunkProjection(1, "짧은 스타일 텍스트")))
        given(docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id))
            .willReturn(
                listOf(
                    chunkEntity(material.userFile.id, 1, "이 강의자료는 개념 설명과 예시 풀이가 함께 있어 답변 스타일 참고용으로 충분한 맥락을 제공합니다. 문장 길이도 충분해서 추출 규칙을 통과할 수 있습니다."),
                    chunkEntity(material.userFile.id, 2, "정의, 비교, 적용 예시가 함께 서술되어 있어 모범 답안의 구성 방식과 어조를 파악하기에 적합한 텍스트입니다.")
                )
            )

        val result = invokeCollectStyleReferenceSnippets(service(), 1L, course, listOf(material))

        assertThat(result).isNotEmpty()
        then(docChunkEmbeddingRepository).should().findAllByUserIdAndUserFileIdOrderByChunkNoAsc(1L, material.userFile.id)
    }

    private fun service() = StudentCourseService(
        studentCourseRepository = studentCourseRepository,
        studentCourseMaterialRepository = studentCourseMaterialRepository,
        studentExamSessionRepository = studentExamSessionRepository,
        studentExamQuestionRepository = studentExamQuestionRepository,
        studentWrongAnswerSetRepository = studentWrongAnswerSetRepository,
        studentWrongAnswerItemRepository = studentWrongAnswerItemRepository,
        studentCourseMaterialVisualAssetRepository = studentCourseMaterialVisualAssetRepository,
        studentCourseYoutubeSummaryJobRepository = studentCourseYoutubeSummaryJobRepository,
        documentIngestionJobRepository = documentIngestionJobRepository,
        docChunkEmbeddingRepository = docChunkEmbeddingRepository,
        embeddingProviderRouter = embeddingProviderRouter,
        documentInterviewService = documentInterviewService,
        interviewAiOrchestrator = interviewAiOrchestrator,
        youTubeTranscriptService = youTubeTranscriptService,
        userRepository = userRepository,
        userGeminiApiKeyService = userGeminiApiKeyService,
        userFileService = userFileService,
        selfProvider = selfProvider
    )

    @Suppress("UNCHECKED_CAST")
    private fun invokeCollectSummarySources(
        service: StudentCourseService,
        userId: Long,
        course: StudentCourse,
        materials: List<StudentCourseMaterial>
    ): List<CourseMaterialSummarySource> {
        val method = StudentCourseService::class.java.getDeclaredMethod(
            "collectSummarySources",
            java.lang.Long.TYPE,
            StudentCourse::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(service, userId, course, materials) as List<CourseMaterialSummarySource>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeCollectMaterialSnippets(
        service: StudentCourseService,
        userId: Long,
        course: StudentCourse,
        materials: List<StudentCourseMaterial>,
        totalLimit: Int
    ): List<String> {
        val method = StudentCourseService::class.java.getDeclaredMethod(
            "collectMaterialSnippets",
            java.lang.Long.TYPE,
            StudentCourse::class.java,
            List::class.java,
            Integer.TYPE
        )
        method.isAccessible = true
        return method.invoke(service, userId, course, materials, totalLimit) as List<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeCollectStyleReferenceSnippets(
        service: StudentCourseService,
        userId: Long,
        course: StudentCourse,
        materials: List<StudentCourseMaterial>
    ): List<String> {
        val method = StudentCourseService::class.java.getDeclaredMethod(
            "collectStyleReferenceSnippets",
            java.lang.Long.TYPE,
            StudentCourse::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(service, userId, course, materials) as List<String>
    }

    private fun createCourse() = StudentCourse(
        id = 100L,
        userId = 1L,
        universityName = "테스트대",
        departmentName = "컴퓨터공학과",
        courseName = "자료구조",
        professorName = "김교수",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun createMaterial(course: StudentCourse): StudentCourseMaterial {
        val user = User(
            id = 1L,
            email = "student@vlainter.com",
            password = "encoded",
            name = "학생",
            status = UserStatus.ACTIVE,
            role = UserRole.USER,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )
        val userFile = UserFile(
            id = 200L,
            user = user,
            fileType = FileType.COURSE_MATERIAL,
            fileUrl = "s3://bucket/material.pdf",
            fileName = "material.pdf",
            originalFileName = "material.pdf",
            storageFileName = "material.pdf",
            storageKey = "uploads/material.pdf",
            contentType = "application/pdf",
            fileSizeBytes = 1024,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )
        return StudentCourseMaterial(
            id = 300L,
            course = course,
            userFile = userFile
        )
    }

    private fun chunkProjection(chunkNo: Int, chunkText: String): ChunkSnippetProjection =
        object : ChunkSnippetProjection {
            override val chunkNo: Int = chunkNo
            override val chunkText: String = chunkText
        }

    private fun chunkEntity(userFileId: Long, chunkNo: Int, chunkText: String) = DocChunkEmbedding(
        id = chunkNo.toLong(),
        userFileId = userFileId,
        userId = 1L,
        chunkNo = chunkNo,
        chunkText = chunkText,
        model = "mock",
        modelVersion = "v1",
        embedding = "[0.1,0.2]"
    )
}
