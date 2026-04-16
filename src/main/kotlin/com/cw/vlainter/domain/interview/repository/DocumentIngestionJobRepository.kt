package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import org.springframework.data.jpa.repository.JpaRepository

interface DocumentIngestionJobRepository : JpaRepository<DocumentIngestionJob, Long> {
    fun findTopByUserIdAndDocumentFileIdOrderByRequestedAtDesc(userId: Long, documentFileId: Long): DocumentIngestionJob?
    fun findTopByDocumentFileIdOrderByRequestedAtDesc(documentFileId: Long): DocumentIngestionJob?
    fun findAllByUserIdAndDocumentFileIdInOrderByDocumentFileIdAscRequestedAtDesc(
        userId: Long,
        documentFileIds: Collection<Long>
    ): List<DocumentIngestionJob>
    fun deleteAllByUserIdAndDocumentFileId(userId: Long, documentFileId: Long): Long
}
