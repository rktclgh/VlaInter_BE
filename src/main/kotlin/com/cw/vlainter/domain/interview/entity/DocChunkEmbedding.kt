package com.cw.vlainter.domain.interview.entity

import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.JdbcTypeCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "doc_chunk_embeddings")
class DocChunkEmbedding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_file_id", nullable = false)
    val userFileId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "chunk_no", nullable = false)
    val chunkNo: Int,

    @Column(name = "chunk_text", nullable = false)
    var chunkText: String,

    @Column(name = "token_count")
    var tokenCount: Int? = null,

    @Column(name = "model", nullable = false, length = 120)
    var model: String,

    @Column(name = "model_version", nullable = false, length = 120)
    var modelVersion: String,

    @Column(name = "embedding", nullable = false, columnDefinition = "vector")
    @ColumnTransformer(write = "?::vector")
    var embedding: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    var metadataJson: String = "{}",

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
