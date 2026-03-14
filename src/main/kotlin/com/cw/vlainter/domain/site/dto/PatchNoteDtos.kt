package com.cw.vlainter.domain.site.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class PublicPatchNoteResponse(
    val patchNoteId: Long,
    val title: String,
    val body: String,
    val sortOrder: Int
)

data class AdminPatchNoteResponse(
    val patchNoteId: Long,
    val title: String,
    val body: String,
    val sortOrder: Int,
    val isPublished: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

data class CreatePatchNoteRequest(
    @field:NotBlank(message = "패치노트 제목을 입력해 주세요.")
    @field:Size(max = 160, message = "패치노트 제목은 160자 이하여야 합니다.")
    val title: String,
    @field:NotBlank(message = "패치노트 본문을 입력해 주세요.")
    @field:Size(max = 5000, message = "패치노트 본문은 5000자 이하여야 합니다.")
    val body: String,
    val sortOrder: Int? = null,
    val isPublished: Boolean = true
)

data class UpdatePatchNoteRequest(
    @field:NotBlank(message = "패치노트 제목을 입력해 주세요.")
    @field:Size(max = 160, message = "패치노트 제목은 160자 이하여야 합니다.")
    val title: String,
    @field:NotBlank(message = "패치노트 본문을 입력해 주세요.")
    @field:Size(max = 5000, message = "패치노트 본문은 5000자 이하여야 합니다.")
    val body: String,
    val sortOrder: Int? = null,
    val isPublished: Boolean = true
)

data class ReorderPatchNotesRequest(
    @field:NotEmpty(message = "정렬할 패치노트가 없습니다.")
    val patchNoteIds: List<Long>
)
