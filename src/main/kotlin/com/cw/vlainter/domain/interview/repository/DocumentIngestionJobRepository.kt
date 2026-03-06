package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import org.springframework.data.jpa.repository.JpaRepository

interface DocumentIngestionJobRepository : JpaRepository<DocumentIngestionJob, Long> {
    fun findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(userId: Long, documentFileId: Long): DocumentIngestionJob?
    fun findTopByDocumentFileIdOrderByRequestedAtDesc(documentFileId: Long): DocumentIngestionJob?
    fun findAllByUserIdAndStatusOrderByRequestedAtDesc(userId: Long, status: DocumentIngestionStatus): List<DocumentIngestionJob>
}
