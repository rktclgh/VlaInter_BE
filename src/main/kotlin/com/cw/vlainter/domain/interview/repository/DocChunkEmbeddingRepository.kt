package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocChunkEmbedding
import org.springframework.data.jpa.repository.JpaRepository

interface DocChunkEmbeddingRepository : JpaRepository<DocChunkEmbedding, Long> {
    fun findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId: Long, userFileId: Long): List<DocChunkEmbedding>
    fun deleteAllByUserIdAndUserFileId(userId: Long, userFileId: Long)
}
