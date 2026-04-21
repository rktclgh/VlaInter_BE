@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.userFile.service

import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialVisualAssetType
import com.cw.vlainter.domain.student.entity.StudentCourse
import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import com.cw.vlainter.domain.student.entity.StudentCourseMaterialVisualAsset
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialVisualAssetRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.config.properties.S3Properties
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.mockito.junit.jupiter.MockitoExtension
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.time.OffsetDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class UserFileServiceTests {
    @org.mockito.Mock private lateinit var userRepository: UserRepository
    @org.mockito.Mock private lateinit var userFileRepository: UserFileRepository
    @org.mockito.Mock private lateinit var docChunkEmbeddingRepository: DocChunkEmbeddingRepository
    @org.mockito.Mock private lateinit var documentIngestionJobRepository: DocumentIngestionJobRepository
    @org.mockito.Mock private lateinit var studentCourseMaterialRepository: StudentCourseMaterialRepository
    @org.mockito.Mock private lateinit var studentCourseMaterialVisualAssetRepository: StudentCourseMaterialVisualAssetRepository
    @org.mockito.Mock private lateinit var s3Client: S3Client

    @Test
    fun `강의자료 삭제 시 material 기준 visual asset과 S3 오브젝트를 함께 정리한다`() {
        val owner = createUser()
        val targetFile = createUserFile(id = 10L, user = owner, storageKey = "uploads/course-material/original.pdf")
        val course = createCourse(owner.id)
        val material = StudentCourseMaterial(
            id = 20L,
            course = course,
            userFile = targetFile
        )
        val assetUserFile1 = createUserFile(id = 11L, user = owner, storageKey = "uploads/course-material/asset-1.png")
        val assetUserFile2 = createUserFile(id = 12L, user = owner, storageKey = "uploads/course-material/asset-2.png")
        val visualAssets = listOf(
            StudentCourseMaterialVisualAsset(
                id = 30L,
                material = material,
                userFile = assetUserFile1,
                assetType = StudentCourseMaterialVisualAssetType.PDF_PAGE_RENDER,
                assetOrder = 1,
                label = "page-1",
                storageKey = "uploads/course-material/asset-1.png"
            ),
            StudentCourseMaterialVisualAsset(
                id = 31L,
                material = material,
                userFile = assetUserFile2,
                assetType = StudentCourseMaterialVisualAssetType.PDF_PAGE_RENDER,
                assetOrder = 2,
                label = "page-2",
                storageKey = "uploads/course-material/asset-2.png"
            )
        )

        given(userRepository.findById(owner.id)).willReturn(Optional.of(owner))
        given(userFileRepository.findById(targetFile.id)).willReturn(Optional.of(targetFile))
        given(studentCourseMaterialRepository.findByUserFile_Id(targetFile.id)).willReturn(material)
        given(studentCourseMaterialVisualAssetRepository.findAllByMaterial_IdOrderByAssetOrderAsc(material.id))
            .willReturn(visualAssets)

        service().deleteOwnedFile(owner.id, targetFile.id)

        then(docChunkEmbeddingRepository).should().deleteAllByUserIdAndUserFileId(owner.id, targetFile.id)
        then(documentIngestionJobRepository).should().deleteAllByUserIdAndDocumentFileId(owner.id, targetFile.id)
        then(studentCourseMaterialVisualAssetRepository).should().deleteAllByMaterialId(material.id)
        then(studentCourseMaterialVisualAssetRepository).should(never()).deleteAllByUserFileId(targetFile.id)
        then(studentCourseMaterialRepository).should().deleteAllByUserFileId(targetFile.id)
        then(userFileRepository).should().delete(targetFile)

        val requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        then(s3Client).should(org.mockito.Mockito.times(3)).deleteObject(requestCaptor.capture())
        val deletedKeys = requestCaptor.allValues.map { it.key() }
        assertThat(deletedKeys).containsExactlyInAnyOrder(
            "uploads/course-material/original.pdf",
            "uploads/course-material/asset-1.png",
            "uploads/course-material/asset-2.png"
        )
    }

    private fun service() = UserFileService(
        userRepository = userRepository,
        userFileRepository = userFileRepository,
        docChunkEmbeddingRepository = docChunkEmbeddingRepository,
        documentIngestionJobRepository = documentIngestionJobRepository,
        studentCourseMaterialRepository = studentCourseMaterialRepository,
        studentCourseMaterialVisualAssetRepository = studentCourseMaterialVisualAssetRepository,
        s3Client = s3Client,
        s3Properties = S3Properties(bucket = "test-bucket"),
        objectMapper = ObjectMapper()
    )

    private fun createUser(id: Long = 1L) = User(
        id = id,
        email = "student@vlainter.com",
        password = "encoded",
        name = "학생",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun createCourse(userId: Long) = StudentCourse(
        id = 100L,
        userId = userId,
        universityName = "테스트대",
        departmentName = "컴퓨터공학과",
        courseName = "자료구조",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun createUserFile(id: Long, user: User, storageKey: String) = UserFile(
        id = id,
        user = user,
        fileType = FileType.COURSE_MATERIAL,
        fileUrl = "s3://test-bucket/$storageKey",
        fileName = storageKey.substringAfterLast('/'),
        originalFileName = storageKey.substringAfterLast('/'),
        storageFileName = storageKey.substringAfterLast('/'),
        storageKey = storageKey,
        contentType = "application/pdf",
        fileSizeBytes = 1024,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
