package com.cw.vlainter.domain.userFile.dto

import com.cw.vlainter.domain.userFile.entity.FileType
import java.time.OffsetDateTime

data class UserFileResponse(
    val fileId: Long,
    val userId: Long,
    val fileType: FileType,
    val fileName: String,
    val fileUrl: String,
    val createdAt: OffsetDateTime
)
