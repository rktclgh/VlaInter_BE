package com.cw.vlainter.domain.site.dto

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
    val title: String,
    val body: String,
    val sortOrder: Int? = null,
    val isPublished: Boolean = true
)

data class UpdatePatchNoteRequest(
    val title: String,
    val body: String,
    val sortOrder: Int? = null,
    val isPublished: Boolean = true
)

data class ReorderPatchNotesRequest(
    val patchNoteIds: List<Long>
)
