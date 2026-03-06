package com.cw.vlainter.domain.interview.entity

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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

@Entity
@Table(
    name = "interview_turns",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_interview_turns_session_turn", columnNames = ["session_id", "turn_no"])
    ]
)
class InterviewTurn(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: InterviewSession,

    @Column(name = "turn_no", nullable = false)
    val turnNo: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_tag", nullable = false, length = 10)
    val sourceTag: TurnSourceTag,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    val question: QaQuestion? = null,

    @Column(name = "question_text_snapshot", nullable = false)
    val questionTextSnapshot: String,

    @Column(name = "category_snapshot", length = 80)
    var categorySnapshot: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: QaCategory? = null,

    @Column(name = "difficulty", length = 20)
    var difficulty: String? = null,

    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    var tagsJson: String = "[]",

    @Column(name = "rag_context", nullable = false, columnDefinition = "jsonb")
    var ragContextJson: String = "{}",

    @Column(name = "served_at", nullable = false)
    var servedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "user_answer")
    var userAnswer: String? = null,

    @Column(name = "answered_at")
    var answeredAt: OffsetDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_status", nullable = false, length = 20)
    var evaluationStatus: TurnEvaluationStatus = TurnEvaluationStatus.PENDING,

    @Column(name = "is_bookmarked", nullable = false)
    var isBookmarked: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
)
