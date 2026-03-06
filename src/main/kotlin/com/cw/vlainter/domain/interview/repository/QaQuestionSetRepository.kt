package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import org.springframework.data.jpa.repository.JpaRepository

interface QaQuestionSetRepository : JpaRepository<QaQuestionSet, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): QaQuestionSet?

    fun findAllByOwnerUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(userId: Long): List<QaQuestionSet>

    fun findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
        visibility: QuestionSetVisibility,
        status: QuestionSetStatus
    ): List<QaQuestionSet>

    fun findAllByDeletedAtIsNullOrderByCreatedAtDesc(): List<QaQuestionSet>

    fun findTop10ByEmbeddingStatusAndDeletedAtIsNullOrderByEmbeddingRequestedAtAsc(
        status: com.cw.vlainter.domain.interview.entity.EmbeddingStatus
    ): List<QaQuestionSet>
}
