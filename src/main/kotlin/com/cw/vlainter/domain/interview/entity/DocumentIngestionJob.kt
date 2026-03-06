package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "document_ingestion_jobs")
class DocumentIngestionJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "document_file_id", nullable = false)
    val documentFileId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: DocumentIngestionStatus,

    @Column(name = "error_message")
    var errorMessage: String? = null,

    @Column(name = "parser_name", length = 100)
    var parserName: String? = null,

    @Column(name = "embedding_model", length = 120)
    var embeddingModel: String? = null,

    @Column(name = "embedding_version", length = 120)
    var embeddingVersion: String? = null,

    @Column(name = "chunk_count")
    var chunkCount: Int? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    var metadataJson: String = "{}",

    @Column(name = "requested_at", nullable = false)
    var requestedAt: OffsetDateTime? = null,

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
) {
    @PrePersist
    fun prePersist() {
        val now = OffsetDateTime.now()
        requestedAt = requestedAt ?: now
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
