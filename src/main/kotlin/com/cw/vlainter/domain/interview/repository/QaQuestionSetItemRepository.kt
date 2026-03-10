package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QaQuestionSetItemRepository : JpaRepository<QaQuestionSetItem, Long> {
    fun findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(setId: Long): List<QaQuestionSetItem>
    fun findAllBySet_IdOrderByOrderNoAsc(setId: Long): List<QaQuestionSetItem>
    fun findBySet_IdAndQuestion_IdAndIsActiveTrue(setId: Long, questionId: Long): QaQuestionSetItem?
    fun countBySet_IdAndIsActiveTrue(setId: Long): Long
    fun countBySet_IdAndIsActiveTrueAndQuestion_SourceTag(setId: Long, sourceTag: QuestionSourceTag): Long

    fun existsBySet_IdAndQuestion_Id(setId: Long, questionId: Long): Boolean

    @Query(
        """
        select count(i) > 0
        from QaQuestionSetItem i
        where i.question.id = :questionId
          and i.isActive = true
          and i.set.deletedAt is null
          and (
            i.set.ownerUser.id = :userId
            or (
              i.set.visibility = com.cw.vlainter.domain.interview.entity.QuestionSetVisibility.GLOBAL
              and i.set.status = com.cw.vlainter.domain.interview.entity.QuestionSetStatus.ACTIVE
            )
          )
        """
    )
    fun existsAccessibleByQuestionIdAndUserId(
        @Param("questionId") questionId: Long,
        @Param("userId") userId: Long
    ): Boolean

    @Query(
        """
        select count(i) > 0
        from QaQuestionSetItem i
        where i.question.id = :questionId
          and i.set.deletedAt is null
          and i.set.ownerType = com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType.USER
          and (
            select count(allItems)
            from QaQuestionSetItem allItems
            where allItems.set = i.set
              and allItems.isActive = true
          ) > 0
          and (
            select count(systemItems)
            from QaQuestionSetItem systemItems
            where systemItems.set = i.set
              and systemItems.isActive = true
              and systemItems.question.sourceTag = com.cw.vlainter.domain.interview.entity.QuestionSourceTag.SYSTEM
          ) = (
            select count(allItems)
            from QaQuestionSetItem allItems
            where allItems.set = i.set
              and allItems.isActive = true
          )
        """
    )
    fun existsInAiGeneratedSetByQuestionId(@Param("questionId") questionId: Long): Boolean

    @Query("select coalesce(max(i.orderNo), 0) from QaQuestionSetItem i where i.set.id = :setId")
    fun findMaxOrderNo(@Param("setId") setId: Long): Int
}
