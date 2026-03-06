package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(name = "interview_turn_evaluations")
class InterviewTurnEvaluation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id", nullable = false, unique = true)
    val turn: InterviewTurn,

    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    var totalScore: BigDecimal,

    @Column(name = "rubric_scores", nullable = false, columnDefinition = "jsonb")
    var rubricScoresJson: String = "{}",

    @Column(name = "feedback", nullable = false)
    var feedback: String,

    @Column(name = "best_practice", nullable = false)
    var bestPractice: String,

    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    var evidenceJson: String = "[]",

    @Column(name = "model", length = 120)
    var model: String? = null,

    @Column(name = "model_version", length = 120)
    var modelVersion: String? = null,

    @Column(name = "evaluated_at", nullable = false)
    var evaluatedAt: OffsetDateTime = OffsetDateTime.now()
)
