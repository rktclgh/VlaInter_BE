package com.cw.vlainter.domain.userFile.service

import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialVisualAssetRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.dto.UserFileResponse
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.repository.UserFileRepository
import com.cw.vlainter.global.config.properties.S3Properties
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

@Service
class UserFileService(
    private val userRepository: UserRepository,
    private val userFileRepository: UserFileRepository,
    private val docChunkEmbeddingRepository: DocChunkEmbeddingRepository,
    private val documentIngestionJobRepository: DocumentIngestionJobRepository,
    private val studentCourseMaterialRepository: StudentCourseMaterialRepository,
    private val studentCourseMaterialVisualAssetRepository: StudentCourseMaterialVisualAssetRepository,
    private val s3Client: S3Client,
    private val s3Properties: S3Properties,
    private val objectMapper: ObjectMapper
) {
    private companion object {
        val ALLOWED_PROFILE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        val ALLOWED_PROFILE_IMAGE_CONTENT_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        val ALLOWED_INTERVIEW_DOCUMENT_EXTENSIONS = setOf("pdf", "docx", "pptx")
        val ALLOWED_INTERVIEW_DOCUMENT_CONTENT_TYPES = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        val ALLOWED_PAST_EXAM_EXTENSIONS = setOf("pdf", "docx", "pptx", "jpg", "jpeg", "png")
        val ALLOWED_PAST_EXAM_CONTENT_TYPES = ALLOWED_INTERVIEW_DOCUMENT_CONTENT_TYPES + setOf(
            "image/jpeg",
            "image/png"
        )
        const val MAX_DOCUMENT_FILES_PER_TYPE = 5L
        const val MAX_COURSE_MATERIAL_FILES = 20L
    }

    private val logger = LoggerFactory.getLogger(UserFileService::class.java)
    private val originalFileNameMaxLength = 255

    @Transactional(readOnly = true)
    fun getMyFiles(principal: AuthPrincipal): List<UserFileResponse> {
        val actor = loadActiveUser(principal.userId)
        return userFileRepository.findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(actor.id)
            .map { toResponse(it) }
    }

    @Transactional(readOnly = true)
    fun loadOwnedFile(userId: Long, fileId: Long): UserFile {
        loadActiveUser(userId)
        return userFileRepository.findByIdAndUser_IdAndDeletedAtIsNull(fileId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "파일 정보를 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    fun getMyProfileImage(principal: AuthPrincipal): ProfileImageResource? {
        val actor = loadActiveUser(principal.userId)
        ensureS3Configured()

        val profileImage = userFileRepository.findTopByUser_IdAndFileTypeAndIsActiveTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
            actor.id,
            FileType.PROFILE_IMAGE
        ) ?: return null

        val objectKey = resolveDeletionKey(profileImage.storageKey, profileImage.fileUrl)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "프로필 이미지를 찾을 수 없습니다.")

        val request = GetObjectRequest.builder()
            .bucket(s3Properties.bucket.trim())
            .key(objectKey)
            .build()

        val responseBytes = try {
            s3Client.getObjectAsBytes(request)
        } catch (ex: S3Exception) {
            if (ex.statusCode() == 404) {
                return null
            }
            logger.warn("S3 profile image fetch failed key={} reason={}", objectKey, ex.message)
            return null
        } catch (ex: SdkClientException) {
            logger.warn("S3 profile image fetch skipped key={} reason={}", objectKey, ex.message)
            return null
        } catch (ex: Exception) {
            logger.warn("S3 profile image fetch failed key={} reason={}", objectKey, ex.message)
            return null
        }

        val contentType = profileImage.contentType?.takeIf { it.isNotBlank() }
            ?: responseBytes.response().contentType()?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        return ProfileImageResource(
            bytes = responseBytes.asByteArray(),
            contentType = contentType
        )
    }

    @Transactional
    fun uploadMyFile(
        principal: AuthPrincipal,
        fileType: FileType,
        file: MultipartFile,
        storedDisplayFileName: String? = null,
        allowedExtensions: Set<String>? = null,
        allowedContentTypes: Set<String>? = null,
        invalidTypeMessage: String? = null
    ): UserFileResponse {
        val actor = userRepository.findByIdForUpdate(principal.userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        if (actor.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 상태 계정은 파일 기능을 사용할 수 없습니다.")
        }
        validateUploadFile(fileType, file, allowedExtensions, allowedContentTypes, invalidTypeMessage)
        ensureS3Configured()

        val originalFileName = extractOriginalFileName(file.originalFilename)
        val effectiveFileName = storedDisplayFileName?.trim()?.takeIf { it.isNotBlank() } ?: originalFileName
        val storageFileName = buildStorageFileName(originalFileName)
        val objectKey = buildObjectKey(actor.id, fileType, storageFileName)
        val storedPath = buildStoredPath(objectKey)
        val contentType = file.contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        if (fileType != FileType.PROFILE_IMAGE) {
            val currentCount = userFileRepository.countByUser_IdAndFileTypeAndDeletedAtIsNull(actor.id, fileType)
            val maxFilesPerType = when (fileType) {
                FileType.COURSE_MATERIAL -> MAX_COURSE_MATERIAL_FILES
                else -> MAX_DOCUMENT_FILES_PER_TYPE
            }
            if (currentCount >= maxFilesPerType) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "${fileType.koreanLabel()} 파일은 최대 ${maxFilesPerType}개까지 보관할 수 있습니다."
                )
            }
        }

        putObject(objectKey, contentType, file.bytes)

        val saved = try {
            if (fileType == FileType.PROFILE_IMAGE) {
                val existing = userFileRepository
                    .findTopByUser_IdAndFileTypeAndIsActiveTrueAndDeletedAtIsNullOrderByCreatedAtDesc(actor.id, fileType)
                if (existing != null) {
                    val oldDeletionKey = resolveDeletionKey(existing.storageKey, existing.fileUrl)
                    userFileRepository.delete(existing)
                    userFileRepository.flush()
                    if (!oldDeletionKey.isNullOrBlank() && oldDeletionKey != objectKey) {
                        runAfterCommit {
                            deleteObjectQuietly(oldDeletionKey)
                        }
                    }
                }
            }

            userFileRepository.save(
                UserFile(
                    user = actor,
                    fileType = fileType,
                    fileUrl = storedPath,
                    fileName = effectiveFileName,
                    originalFileName = originalFileName,
                    storageFileName = storageFileName,
                    storageKey = objectKey,
                    contentType = contentType,
                    fileSizeBytes = file.size,
                    isActive = true,
                    updatedAt = OffsetDateTime.now()
                )
            )
        } catch (ex: Exception) {
            deleteObjectQuietly(objectKey)
            throw ex
        }

        return toResponse(saved)
    }

    @Transactional
    fun deleteFile(principal: AuthPrincipal, fileId: Long) {
        val actor = loadActiveUser(principal.userId)
        val target = userFileRepository.findById(fileId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "파일 정보를 찾을 수 없습니다.") }

        if (actor.role != UserRole.ADMIN && target.user.id != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 파일만 삭제할 수 있습니다.")
        }

        deleteFileInternal(target)
    }

    @Transactional
    fun deleteOwnedFile(userId: Long, fileId: Long) {
        val actor = loadActiveUser(userId)
        val target = userFileRepository.findById(fileId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "파일 정보를 찾을 수 없습니다.") }

        if (target.user.id != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 파일만 삭제할 수 있습니다.")
        }

        deleteFileInternal(target)
    }

    @Transactional(readOnly = true)
    fun getOwnedFileContent(userId: Long, fileId: Long): FileContentResource {
        val target = loadOwnedFile(userId, fileId)
        return loadStoredObjectContent(
            storageKey = resolveDeletionKey(target.storageKey, target.fileUrl)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "파일 저장 경로를 찾을 수 없습니다."),
            downloadFileName = target.originalFileName,
            fallbackContentType = target.contentType
        )
    }

    @Transactional(readOnly = true)
    fun loadStoredObjectContent(
        storageKey: String,
        downloadFileName: String,
        fallbackContentType: String? = null
    ): FileContentResource {
        ensureS3Configured()
        val request = GetObjectRequest.builder()
            .bucket(s3Properties.bucket.trim())
            .key(storageKey)
            .build()

        val responseBytes = try {
            s3Client.getObjectAsBytes(request)
        } catch (ex: S3Exception) {
            if (ex.statusCode() == 404) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다.")
            }
            logger.warn("S3 object fetch failed key={} reason={}", storageKey, ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "파일을 불러오지 못했습니다.")
        } catch (ex: SdkClientException) {
            logger.warn("S3 object fetch skipped key={} reason={}", storageKey, ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "파일 저장소에 연결하지 못했습니다.")
        } catch (ex: Exception) {
            logger.warn("S3 object fetch failed key={} reason={}", storageKey, ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "파일을 불러오지 못했습니다.")
        }

        val contentType = fallbackContentType?.takeIf { it.isNotBlank() }
            ?: responseBytes.response().contentType()?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        return FileContentResource(
            bytes = responseBytes.asByteArray(),
            contentType = contentType,
            fileName = downloadFileName
        )
    }

    private fun deleteFileInternal(target: UserFile) {
        val deletionKey = resolveDeletionKey(target.storageKey, target.fileUrl)
        val targetOwnerId = target.user.id

        if (target.fileType.isInterviewDocument() || target.fileType == FileType.COURSE_MATERIAL) {
            // 문서 삭제 시 임베딩/ingestion 이력도 함께 정리한다.
            docChunkEmbeddingRepository.deleteAllByUserIdAndUserFileId(targetOwnerId, target.id)
            documentIngestionJobRepository.deleteAllByUserIdAndDocumentFileId(targetOwnerId, target.id)
            studentCourseMaterialVisualAssetRepository.deleteAllByUserFile_Id(target.id)
            studentCourseMaterialRepository.findByUserFile_Id(target.id)?.let { studentCourseMaterialRepository.delete(it) }
        }

        userFileRepository.delete(target)
        runAfterCommit {
            deleteObjectQuietly(deletionKey)
        }
    }

    private fun loadActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }

        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 상태 계정은 파일 기능을 사용할 수 없습니다.")
        }
        return user
    }

    private fun validateUploadFile(
        fileType: FileType,
        file: MultipartFile,
        allowedExtensions: Set<String>? = null,
        allowedContentTypes: Set<String>? = null,
        invalidTypeMessage: String? = null
    ) {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드할 파일이 비어 있습니다.")
        }
        if (file.size > s3Properties.maxFileSizeBytes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "파일 크기는 ${s3Properties.maxFileSizeBytes} bytes 이하여야 합니다."
            )
        }

        val lowerName = file.originalFilename?.trim()?.lowercase().orEmpty()
        val contentType = file.contentType?.trim()?.lowercase().orEmpty()
        val extension = lowerName.substringAfterLast('.', "")

        if (fileType == FileType.RESUME || fileType == FileType.INTRODUCE || fileType == FileType.PORTFOLIO || fileType == FileType.COURSE_MATERIAL) {
            val targetExtensions = allowedExtensions ?: ALLOWED_INTERVIEW_DOCUMENT_EXTENSIONS
            val targetContentTypes = allowedContentTypes ?: ALLOWED_INTERVIEW_DOCUMENT_CONTENT_TYPES
            val extensionValid = extension in targetExtensions
            val contentTypeValid = contentType.isNotBlank() && contentType in targetContentTypes
            if (!extensionValid || !contentTypeValid) {
                val dynamicInvalidTypeMessage = buildDocumentTypeErrorMessage(targetExtensions, targetContentTypes)
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    invalidTypeMessage ?: dynamicInvalidTypeMessage
                )
            }
        }

        if (fileType == FileType.PROFILE_IMAGE) {
            val extensionValid = extension in ALLOWED_PROFILE_IMAGE_EXTENSIONS
            val contentTypeValid = contentType.isNotBlank() && contentType in ALLOWED_PROFILE_IMAGE_CONTENT_TYPES
            if (!extensionValid || !contentTypeValid) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로필 이미지는 PNG/JPG/JPEG/WEBP 형식만 업로드할 수 있습니다."
                )
            }
        }
    }

    private fun ensureS3Configured() {
        if (s3Properties.bucket.isBlank()) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 버킷 설정이 누락되었습니다.")
        }
    }

    private fun putObject(objectKey: String, contentType: String, bytes: ByteArray) {
        val request = PutObjectRequest.builder()
            .bucket(s3Properties.bucket.trim())
            .key(objectKey)
            .contentType(contentType)
            .build()

        runCatching {
            s3Client.putObject(request, RequestBody.fromBytes(bytes))
        }.getOrElse { ex ->
            logger.warn("S3 upload failed key={} reason={}", objectKey, ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "파일 업로드에 실패했습니다.")
        }
    }

    private fun buildObjectKey(userId: Long, fileType: FileType, fileName: String): String {
        val now = OffsetDateTime.now()
        val prefix = s3Properties.keyPrefix.trim().trim('/')
        val month = now.monthValue.toString().padStart(2, '0')
        val typeSegment = when (fileType) {
            FileType.RESUME -> "resume"
            FileType.INTRODUCE -> "introduce"
            FileType.PORTFOLIO -> "portfolio"
            FileType.PROFILE_IMAGE -> "profile-image"
            FileType.COURSE_MATERIAL -> "course-material"
        }
        return "$prefix/users/$userId/$typeSegment/${now.year}/$month/$fileName"
    }

    private fun buildStoredPath(objectKey: String): String {
        val bucket = s3Properties.bucket.trim()
        return "s3://$bucket/$objectKey"
    }

    private fun extractOriginalFileName(originalFileName: String?): String {
        val candidate = originalFileName?.trim().orEmpty()
        val withoutPath = candidate.substringAfterLast('/').substringAfterLast('\\')
        val normalizedWhitespace = withoutPath
            .replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .replace("\u0000", "")
            .trim()

        val safeName = normalizedWhitespace.ifBlank { "file" }
        return safeName.take(originalFileNameMaxLength)
    }

    private fun buildStorageFileName(originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

        val objectId = UUID.randomUUID().toString()
        return if (extension.isBlank()) objectId else "$objectId.$extension"
    }

    private fun resolveDeletionKey(storageKey: String?, storedPath: String?): String? {
        if (!storageKey.isNullOrBlank()) {
            return storageKey.trim()
        }
        if (storedPath.isNullOrBlank()) {
            return null
        }
        val parsed = resolveObjectKey(storedPath)
        return parsed.ifBlank { null }
    }

    private fun resolveObjectKey(storedPath: String): String {
        val trimmed = storedPath.trim()
        val bucket = s3Properties.bucket.trim()

        if (trimmed.startsWith("s3://")) {
            val prefix = "s3://$bucket/"
            if (trimmed.startsWith(prefix)) {
                return trimmed.removePrefix(prefix)
            }
            return trimmed.removePrefix("s3://").substringAfter('/')
        }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val uri = URI(trimmed)
            return uri.path.trimStart('/')
        }

        return trimmed
    }

    private fun deleteObjectQuietly(objectKey: String?) {
        if (objectKey.isNullOrBlank() || s3Properties.bucket.isBlank()) return

        val request = DeleteObjectRequest.builder()
            .bucket(s3Properties.bucket.trim())
            .key(objectKey)
            .build()

        runCatching {
            s3Client.deleteObject(request)
        }.onFailure { ex ->
            logger.warn("S3 delete failed key={} reason={}", objectKey, ex.message)
        }
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

    private fun buildDocumentTypeErrorMessage(
        targetExtensions: Set<String>,
        targetContentTypes: Set<String>
    ): String {
        val extensionPart = targetExtensions
            .map { it.uppercase() }
            .sorted()
            .joinToString(", ")
        val contentTypePart = targetContentTypes
            .sorted()
            .joinToString(", ")
        return "문서 자료는 다음 형식만 업로드할 수 있습니다. 확장자: $extensionPart / MIME: $contentTypePart"
    }

    private fun toResponse(file: UserFile): UserFileResponse {
        val displayFileName = file.fileName.takeIf { it.isNotBlank() } ?: file.originalFileName
        val latestIngestionJob = if (file.fileType == FileType.PROFILE_IMAGE) null
        else documentIngestionJobRepository.findTopByDocumentFileIdOrderByRequestedAtDesc(file.id)
        val extractionMethod = extractMetadataExtractionMethod(latestIngestionJob?.metadataJson)
        return UserFileResponse(
            fileId = file.id,
            fileType = file.fileType,
            fileName = displayFileName,
            fileUrl = file.fileUrl,
            createdAt = file.createdAt,
            originalFileName = file.originalFileName,
            storageFileName = file.storageFileName,
            versionNo = file.versionNo,
            active = file.isActive,
            ingestionStatus = latestIngestionJob?.status?.name,
            ingested = latestIngestionJob?.status == DocumentIngestionStatus.READY,
            extractionMethod = extractionMethod,
            ocrUsed = extractionMethod == "OCR_TESSERACT"
        )
    }

    private fun extractMetadataExtractionMethod(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            objectMapper.readTree(rawJson)
                .path("extractionMethod")
                .takeIf { !it.isMissingNode && !it.isNull }
                ?.asText()
                ?.trim()
        }.getOrNull().takeIf { !it.isNullOrBlank() }
    }

    class ProfileImageResource(
        val bytes: ByteArray,
        val contentType: String
    )

    class FileContentResource(
        val bytes: ByteArray,
        val contentType: String,
        val fileName: String
    )

    private fun FileType.koreanLabel(): String = when (this) {
        FileType.RESUME -> "이력서"
        FileType.INTRODUCE -> "자기소개서"
        FileType.PORTFOLIO -> "포트폴리오"
        FileType.PROFILE_IMAGE -> "프로필 이미지"
        FileType.COURSE_MATERIAL -> "과목 자료"
    }

    private fun FileType.isInterviewDocument(): Boolean {
        return this == FileType.RESUME || this == FileType.INTRODUCE || this == FileType.PORTFOLIO
    }

    fun allowedPastExamExtensions(): Set<String> = ALLOWED_PAST_EXAM_EXTENSIONS

    fun allowedPastExamContentTypes(): Set<String> = ALLOWED_PAST_EXAM_CONTENT_TYPES
}
