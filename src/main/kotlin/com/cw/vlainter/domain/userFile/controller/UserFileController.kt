package com.cw.vlainter.domain.userFile.controller

import com.cw.vlainter.domain.userFile.dto.UserFileResponse
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.service.UserFileService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/users/files")
class UserFileController(
    private val userFileService: UserFileService
) {
    @GetMapping
    fun getMyFiles(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<UserFileResponse>> {
        return ResponseEntity.ok(userFileService.getMyFiles(principal))
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadMyFile(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam fileType: String,
        @RequestParam file: MultipartFile
    ): ResponseEntity<UserFileResponse> {
        val resolvedFileType = resolveFileType(fileType)
        val uploaded = userFileService.uploadMyFile(principal, resolvedFileType, file)
        return ResponseEntity.ok(uploaded)
    }

    @DeleteMapping("/{fileId}")
    fun deleteFile(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable fileId: Long
    ): ResponseEntity<Map<String, String>> {
        userFileService.deleteFile(principal, fileId)
        return ResponseEntity.ok(mapOf("message" to "파일이 삭제되었습니다."))
    }

    private fun resolveFileType(raw: String): FileType {
        val normalized = raw.trim().uppercase()
        return runCatching { FileType.valueOf(normalized) }
            .getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 fileType 입니다: $raw")
            }
    }
}
