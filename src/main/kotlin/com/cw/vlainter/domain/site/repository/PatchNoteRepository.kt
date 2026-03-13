package com.cw.vlainter.domain.site.repository

import com.cw.vlainter.domain.site.entity.PatchNote
import org.springframework.data.jpa.repository.JpaRepository

interface PatchNoteRepository : JpaRepository<PatchNote, Long> {
    fun findAllByIsPublishedTrueOrderBySortOrderAscCreatedAtDesc(): List<PatchNote>
    fun findAllByOrderBySortOrderAscCreatedAtDesc(): List<PatchNote>
}
