@file:Suppress("JpaDataSourceORMInspection")

package com.cw.vlainter.domain.student.entity

import com.cw.vlainter.domain.student.dto.StudentExamQuestionStyle
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "student_exam_questions")
class StudentExamQuestion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Suppress("unused")
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Suppress("unused")
    @Column(name = "question_order", nullable = false)
    val questionOrder: Int,

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    val questionText: String,

    @Column(name = "question_style", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    val questionStyle: StudentExamQuestionStyle,

    @Column(name = "canonical_answer", nullable = false, columnDefinition = "text")
    val canonicalAnswer: String,

    @Column(name = "grading_criteria", nullable = false, columnDefinition = "text")
    val gradingCriteria: String,

    @Column(name = "reference_example", columnDefinition = "text")
    val referenceExample: String? = null,

    @Column(name = "max_score", nullable = false)
    val maxScore: Int = 20,

    @Column(name = "answer_text", columnDefinition = "text")
    var answerText: String? = null,

    @Column(name = "score")
    var score: Int? = null,

    @Column(name = "feedback", columnDefinition = "text")
    var feedback: String? = null,

    @Column(name = "is_correct")
    var isCorrect: Boolean? = null,

    @Column(name = "answered_at")
    var answeredAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
) {
    @PrePersist
    fun prePersist() {
        createdAt = OffsetDateTime.now()
    }
}
