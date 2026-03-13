package com.cw.vlainter.domain.site.controller

import com.cw.vlainter.domain.site.dto.AdminPatchNoteResponse
import com.cw.vlainter.domain.site.dto.CreatePatchNoteRequest
import com.cw.vlainter.domain.site.dto.UpdatePatchNoteRequest
import com.cw.vlainter.domain.site.service.PatchNoteService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/site/patch-notes")
class AdminPatchNoteController(
    private val patchNoteService: PatchNoteService
) {
    @GetMapping
    fun getPatchNotes(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<AdminPatchNoteResponse>> {
        return ResponseEntity.ok(patchNoteService.getAdminPatchNotes(principal))
    }

    @PostMapping
    fun createPatchNote(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestBody request: CreatePatchNoteRequest
    ): ResponseEntity<AdminPatchNoteResponse> {
        return ResponseEntity.ok(patchNoteService.createPatchNote(principal, request))
    }

    @PatchMapping("/{patchNoteId}")
    fun updatePatchNote(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable patchNoteId: Long,
        @RequestBody request: UpdatePatchNoteRequest
    ): ResponseEntity<AdminPatchNoteResponse> {
        return ResponseEntity.ok(patchNoteService.updatePatchNote(principal, patchNoteId, request))
    }

    @DeleteMapping("/{patchNoteId}")
    fun deletePatchNote(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable patchNoteId: Long
    ): ResponseEntity<Map<String, String>> {
        patchNoteService.deletePatchNote(principal, patchNoteId)
        return ResponseEntity.ok(mapOf("message" to "패치노트가 삭제되었습니다."))
    }
}
