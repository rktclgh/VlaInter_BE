package com.cw.vlainter.domain.interview.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "qa_question_sets")
class QaQuestionSet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    var ownerUser: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    var ownerType: QuestionSetOwnerType,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "job_name", length = 120)
    var jobName: String? = null,

    @Column(name = "skill_name", length = 120)
    var skillName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    var visibility: QuestionSetVisibility = QuestionSetVisibility.PRIVATE,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: QuestionSetStatus = QuestionSetStatus.ACTIVE,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_set_id")
    var sourceSet: QaQuestionSet? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promoted_by")
    var promotedBy: User? = null,

    @Column(name = "promoted_at")
    var promotedAt: OffsetDateTime? = null,

    @Column(name = "is_promoted", nullable = false)
    var isPromoted: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null
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
