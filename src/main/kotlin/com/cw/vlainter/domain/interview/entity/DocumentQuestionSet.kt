package com.cw.vlainter.domain.interview.entity

import com.cw.vlainter.domain.userFile.entity.FileType
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
@Table(name = "document_question_sets")
class DocumentQuestionSet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "document_file_id", nullable = false)
    val documentFileId: Long,

    @Column(name = "ingestion_job_id")
    var ingestionJobId: Long? = null,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source_file_type", nullable = false, columnDefinition = "file_type")
    var sourceFileType: FileType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_context", nullable = false, columnDefinition = "jsonb")
    var generationContextJson: String = "{}",

    @Column(name = "question_count", nullable = false)
    var questionCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
) {
    @PrePersist
    fun prePersist() {
        val now = OffsetDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
