@file:Suppress("JpaDataSourceORMInspection")

package com.cw.vlainter.domain.student.entity

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
import java.time.OffsetDateTime

@Entity
@Table(name = "student_exam_sessions")
class StudentExamSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "course_id", nullable = false)
    val courseId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    var status: StudentExamSessionStatus = StudentExamSessionStatus.READY,

    @Column(name = "question_count", nullable = false)
    var questionCount: Int,

    @Column(name = "source_material_count", nullable = false)
    var sourceMaterialCount: Int,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Column(name = "answered_count", nullable = false)
    var answeredCount: Int = 0,

    @Column(name = "total_score")
    var totalScore: Int? = null,

    @Column(name = "submitted_at")
    var submittedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
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
