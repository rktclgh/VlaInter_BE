package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "document_questions")
class DocumentQuestion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "set_id", nullable = false)
    val setId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "document_file_id", nullable = false)
    val documentFileId: Long,

    @Column(name = "question_no", nullable = false)
    val questionNo: Int,

    @Column(name = "question_text", nullable = false)
    var questionText: String,

    @Column(name = "question_type", nullable = false, length = 30)
    var questionType: String,

    @Column(name = "difficulty", length = 20)
    var difficulty: String? = null,

    @Column(name = "reference_answer")
    var referenceAnswer: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    var evidenceJson: String = "[]",

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
