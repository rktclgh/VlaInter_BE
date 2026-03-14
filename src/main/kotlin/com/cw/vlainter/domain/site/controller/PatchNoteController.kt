package com.cw.vlainter.domain.site.controller

import com.cw.vlainter.domain.site.dto.PublicPatchNoteResponse
import com.cw.vlainter.domain.site.service.PatchNoteService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/site/patch-notes")
class PatchNoteController(
    private val patchNoteService: PatchNoteService
) {
    @GetMapping
    fun getPublishedPatchNotes(): ResponseEntity<List<PublicPatchNoteResponse>> {
        return ResponseEntity.ok(patchNoteService.getPublishedPatchNotes())
    }
}
