package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestionEmbedding
import org.springframework.data.jpa.repository.JpaRepository

interface QaQuestionEmbeddingRepository : JpaRepository<QaQuestionEmbedding, Long> {
    fun findByQuestion_IdAndModelAndModelVersion(
        questionId: Long,
        model: String,
        modelVersion: String
    ): QaQuestionEmbedding?
}
