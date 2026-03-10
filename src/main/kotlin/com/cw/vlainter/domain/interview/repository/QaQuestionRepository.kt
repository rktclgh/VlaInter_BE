package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.QaCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QaQuestionRepository : JpaRepository<QaQuestion, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): QaQuestion?

    fun findByFingerprintAndDeletedAtIsNull(fingerprint: String): QaQuestion?

    @Query(
        """
        select distinct i.question
        from QaQuestionSetItem i
        join i.set s
        where i.isActive = true
          and s.deletedAt is null
          and s.status = :setStatus
          and (s.visibility = :globalVisibility or s.ownerUser.id = :userId)
          and i.question.deletedAt is null
          and i.question.isActive = true
          and (:difficulty is null or i.question.difficulty = :difficulty)
          and (:sourceTag is null or i.question.sourceTag = :sourceTag)
        """
    )
    fun findCandidatesForUser(
        @Param("userId") userId: Long,
        @Param("setStatus") setStatus: QuestionSetStatus,
        @Param("globalVisibility") globalVisibility: QuestionSetVisibility,
        @Param("difficulty") difficulty: QuestionDifficulty?,
        @Param("sourceTag") sourceTag: QuestionSourceTag?
    ): List<QaQuestion>

    @Modifying(flushAutomatically = true)
    @Query("update QaQuestion q set q.category = :target where q.category = :source")
    fun reassignCategory(
        @Param("source") source: QaCategory,
        @Param("target") target: QaCategory
    ): Int
}
