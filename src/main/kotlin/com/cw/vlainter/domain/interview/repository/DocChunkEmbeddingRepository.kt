package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocChunkEmbedding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DocChunkEmbeddingRepository : JpaRepository<DocChunkEmbedding, Long> {
    fun findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId: Long, userFileId: Long): List<DocChunkEmbedding>
    fun countByUserIdAndUserFileId(userId: Long, userFileId: Long): Long
    fun deleteAllByUserIdAndUserFileId(userId: Long, userFileId: Long)

    // pgvector distance operator(<=>) is not expressible in JPQL, so this retrieval stays native.
    @Query(
        value = """
            select
                d.chunk_no as chunkNo,
                d.chunk_text as chunkText
            from doc_chunk_embeddings d
            where d.user_id = :userId
              and d.user_file_id = :userFileId
            order by d.embedding <=> cast(:queryVector as vector), d.chunk_no asc
            limit :limit
        """,
        nativeQuery = true
    )
    fun findTopSemanticMatches(
        @Param("userId") userId: Long,
        @Param("userFileId") userFileId: Long,
        @Param("queryVector") queryVector: String,
        @Param("limit") limit: Int
    ): List<ChunkSnippetProjection>
}

interface ChunkSnippetProjection {
    val chunkNo: Int
    val chunkText: String
}
