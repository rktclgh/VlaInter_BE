package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QaQuestionSetItemRepository : JpaRepository<QaQuestionSetItem, Long> {
    fun findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(setId: Long): List<QaQuestionSetItem>
    fun countBySet_IdAndIsActiveTrue(setId: Long): Long

    fun existsBySet_IdAndQuestion_Id(setId: Long, questionId: Long): Boolean

    @Query(
        """
        select count(i) > 0
        from QaQuestionSetItem i
        where i.question.id = :questionId
          and i.set.deletedAt is null
          and upper(i.set.title) like 'AUTO:%'
        """
    )
    fun existsInAutoSetByQuestionId(@Param("questionId") questionId: Long): Boolean

    @Query("select coalesce(max(i.orderNo), 0) from QaQuestionSetItem i where i.set.id = :setId")
    fun findMaxOrderNo(@Param("setId") setId: Long): Int
}
