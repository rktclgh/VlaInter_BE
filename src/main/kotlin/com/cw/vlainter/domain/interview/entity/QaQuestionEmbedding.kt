package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.ColumnTransformer
import java.time.OffsetDateTime

@Entity
@Table(
    name = "qa_question_embeddings",
    uniqueConstraints = [
        UniqueConstraint(
            name = "qa_question_embeddings_question_id_model_model_version_key",
            columnNames = ["question_id", "model", "model_version"]
        )
    ]
)
class QaQuestionEmbedding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    var question: QaQuestion,

    @Column(name = "model", nullable = false, length = 120)
    var model: String,

    @Column(name = "model_version", nullable = false, length = 120)
    var modelVersion: String,

    @Column(name = "embedding", nullable = false, columnDefinition = "vector")
    @ColumnTransformer(write = "?::vector")
    var embedding: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null
) {
    @PrePersist
    fun prePersist() {
        createdAt = OffsetDateTime.now()
    }
}
