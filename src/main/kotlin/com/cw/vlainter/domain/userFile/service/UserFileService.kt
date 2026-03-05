package com.cw.vlainter.domain.userFile.service

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
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

@Service
class UserFileService(
    private val userRepository: UserRepository,
    private val userFileRepository: UserFileRepository,
    private val s3Client: S3Client,
    private val s3Properties: S3Properties
) {
    private val logger = LoggerFactory.getLogger(UserFileService::class.java)

    @Transactional(readOnly = true)
    fun getMyFiles(principal: AuthPrincipal): List<UserFileResponse> {
        val actor = loadActiveUser(principal.userId)
        return userFileRepository.findAllByUser_IdOrderByCreatedAtDesc(actor.id)
            .map { toResponse(it) }
    }

    @Transactional
    fun uploadMyFile(principal: AuthPrincipal, fileType: FileType, file: MultipartFile): UserFileResponse {
        val actor = loadActiveUser(principal.userId)
        validateUploadFile(fileType, file)
        ensureS3Configured()

        val sanitizedFileName = sanitizeFileName(file.originalFilename)
        val objectKey = buildObjectKey(actor.id, fileType, sanitizedFileName)
        val storedPath = buildStoredPath(objectKey)
        val contentType = file.contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        putObject(objectKey, contentType, file.bytes)

        var oldStoredPath: String? = null
        val saved = try {
            val existing = userFileRepository.findByUser_IdAndFileType(actor.id, fileType)
            if (existing != null) {
                oldStoredPath = existing.fileUrl
                userFileRepository.delete(existing)
            }

            userFileRepository.save(
                UserFile(
                    user = actor,
                    fileType = fileType,
                    fileUrl = storedPath,
                    fileName = sanitizedFileName
                )
            )
        } catch (ex: Exception) {
            deleteObjectQuietly(storedPath)
            throw ex
        }

        if (!oldStoredPath.isNullOrBlank() && oldStoredPath != storedPath) {
            val oldPath = oldStoredPath
            runAfterCommit {
                if (!oldPath.isNullOrBlank()) {
                    deleteObjectQuietly(oldPath)
                }
            }
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

        userFileRepository.delete(target)
        runAfterCommit {
            deleteObjectQuietly(target.fileUrl)
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

    private fun validateUploadFile(fileType: FileType, file: MultipartFile) {
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

        if (fileType == FileType.RESUME || fileType == FileType.INTRODUCE || fileType == FileType.PORTFOLIO) {
            val extensionValid = extension == "pdf"
            val contentTypeValid = contentType.isBlank() || contentType == "application/pdf"
            if (!extensionValid || !contentTypeValid) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이력서/자기소개서/포트폴리오는 PDF 파일만 업로드할 수 있습니다.")
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
        }
        return "$prefix/users/$userId/$typeSegment/${now.year}/$month/${UUID.randomUUID()}-$fileName"
    }

    private fun buildStoredPath(objectKey: String): String {
        val bucket = s3Properties.bucket.trim()
        return "s3://$bucket/$objectKey"
    }

    private fun sanitizeFileName(originalFileName: String?): String {
        val candidate = originalFileName?.trim().orEmpty()
        val withoutPath = candidate.substringAfterLast('/').substringAfterLast('\\')
        val compactWhitespace = withoutPath.replace(Regex("\\s+"), "_")
        val sanitized = compactWhitespace.replace(Regex("[^A-Za-z0-9._-]"), "")
        return sanitized.ifBlank { "file" }
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

    private fun deleteObjectQuietly(storedPath: String) {
        if (storedPath.isBlank() || s3Properties.bucket.isBlank()) return

        val objectKey = resolveObjectKey(storedPath)
        if (objectKey.isBlank()) return

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

    private fun toResponse(file: UserFile): UserFileResponse {
        return UserFileResponse(
            fileId = file.id,
            userId = file.user.id,
            fileType = file.fileType,
            fileName = file.fileName,
            fileUrl = file.fileUrl,
            createdAt = file.createdAt
        )
    }
}
