package com.cw.vlainter.domain.interview.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(
    name = "saved_questions",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_saved_questions_user_turn", columnNames = ["user_id", "source_turn_id"])
    ]
)
class SavedQuestion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    val question: QaQuestion? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_question_id")
    val documentQuestion: DocumentQuestion? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_turn_id")
    val sourceTurn: InterviewTurn? = null,

    @Column(name = "question_text_snapshot", nullable = false)
    var questionTextSnapshot: String,

    @Column(name = "category_snapshot", length = 80)
    var categorySnapshot: String? = null,

    @Column(name = "job_snapshot", length = 120)
    var jobSnapshot: String? = null,

    @Column(name = "skill_snapshot", length = 120)
    var skillSnapshot: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: QaCategory? = null,

    @Column(name = "difficulty", length = 20)
    var difficulty: String? = null,

    @Column(name = "source_tag", length = 10)
    var sourceTag: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    var tagsJson: String = "[]",

    @Column(name = "note")
    var note: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
