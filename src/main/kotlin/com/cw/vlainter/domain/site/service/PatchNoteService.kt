package com.cw.vlainter.domain.site.service

import com.cw.vlainter.domain.site.dto.AdminPatchNoteResponse
import com.cw.vlainter.domain.site.dto.CreatePatchNoteRequest
import com.cw.vlainter.domain.site.dto.PublicPatchNoteResponse
import com.cw.vlainter.domain.site.dto.ReorderPatchNotesRequest
import com.cw.vlainter.domain.site.dto.UpdatePatchNoteRequest
import com.cw.vlainter.domain.site.entity.PatchNote
import com.cw.vlainter.domain.site.repository.PatchNoteRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class PatchNoteService(
    private val patchNoteRepository: PatchNoteRepository
) {
    @Transactional(readOnly = true)
    fun getPublishedPatchNotes(): List<PublicPatchNoteResponse> {
        return patchNoteRepository.findAllByIsPublishedTrueOrderBySortOrderAscCreatedAtDesc()
            .map { it.toPublicResponse() }
    }

    @Transactional(readOnly = true)
    fun getAdminPatchNotes(principal: AuthPrincipal): List<AdminPatchNoteResponse> {
        ensureAdmin(principal)
        return patchNoteRepository.findAllByOrderBySortOrderAscCreatedAtDesc()
            .map { it.toAdminResponse() }
    }

    @Transactional
    fun createPatchNote(principal: AuthPrincipal, request: CreatePatchNoteRequest): AdminPatchNoteResponse {
        ensureAdmin(principal)
        val nextSortOrder = request.sortOrder ?: ((patchNoteRepository.findAllByOrderBySortOrderAscCreatedAtDesc().firstOrNull()?.sortOrder ?: 0) - 10)
        val saved = patchNoteRepository.save(
            PatchNote(
                title = request.title.trim().ifBlank {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "패치노트 제목을 입력해 주세요.")
                },
                body = request.body.trim().ifBlank {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "패치노트 본문을 입력해 주세요.")
                },
                sortOrder = nextSortOrder,
                isPublished = request.isPublished
            )
        )
        return saved.toAdminResponse()
    }

    @Transactional
    fun updatePatchNote(principal: AuthPrincipal, patchNoteId: Long, request: UpdatePatchNoteRequest): AdminPatchNoteResponse {
        ensureAdmin(principal)
        val patchNote = patchNoteRepository.findById(patchNoteId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "패치노트를 찾을 수 없습니다.") }
        patchNote.title = request.title.trim().ifBlank {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "패치노트 제목을 입력해 주세요.")
        }
        patchNote.body = request.body.trim().ifBlank {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "패치노트 본문을 입력해 주세요.")
        }
        request.sortOrder?.let { patchNote.sortOrder = it }
        patchNote.isPublished = request.isPublished
        return patchNoteRepository.save(patchNote).toAdminResponse()
    }

    @Transactional
    fun reorderPatchNotes(principal: AuthPrincipal, request: ReorderPatchNotesRequest): List<AdminPatchNoteResponse> {
        ensureAdmin(principal)
        val requestedIds = request.patchNoteIds.distinct()
        if (requestedIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "정렬할 패치노트가 없습니다.")
        }
        val allPatchNotes = patchNoteRepository.findAllByOrderBySortOrderAscCreatedAtDesc()
        val allIds = allPatchNotes.map { it.id }
        if (!allIds.containsAll(requestedIds)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "일부 패치노트를 찾을 수 없습니다.")
        }
        val remainingIds = allIds.filterNot { requestedIds.contains(it) }
        val orderedIds = requestedIds + remainingIds
        val patchNoteById = allPatchNotes.associateBy { it.id }
        orderedIds.forEachIndexed { index, patchNoteId ->
            patchNoteById[patchNoteId]?.sortOrder = index * 10
        }
        patchNoteRepository.saveAll(allPatchNotes)
        return patchNoteRepository.findAllByOrderBySortOrderAscCreatedAtDesc()
            .map { it.toAdminResponse() }
    }

    @Transactional
    fun deletePatchNote(principal: AuthPrincipal, patchNoteId: Long) {
        ensureAdmin(principal)
        val patchNote = patchNoteRepository.findById(patchNoteId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "패치노트를 찾을 수 없습니다.") }
        patchNoteRepository.delete(patchNote)
    }

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
    }

    private fun PatchNote.toPublicResponse(): PublicPatchNoteResponse = PublicPatchNoteResponse(
        patchNoteId = id,
        title = title,
        body = body,
        sortOrder = sortOrder
    )

    private fun PatchNote.toAdminResponse(): AdminPatchNoteResponse = AdminPatchNoteResponse(
        patchNoteId = id,
        title = title,
        body = body,
        sortOrder = sortOrder,
        isPublished = isPublished,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
