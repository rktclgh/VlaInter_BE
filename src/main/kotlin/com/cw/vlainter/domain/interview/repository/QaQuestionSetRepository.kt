package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QaQuestionSetRepository : JpaRepository<QaQuestionSet, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): QaQuestionSet?

    @Query(
        """
        select q
        from QaQuestionSet q
        where q.ownerUser.id = :userId
          and q.title = :title
          and q.deletedAt is null
        order by q.createdAt desc
        """
    )
    fun findLatestByOwnerUserIdAndTitle(
        @Param("userId") userId: Long,
        @Param("title") title: String
    ): QaQuestionSet?

    @Query(
        """
        select s
        from QaQuestionSet s
        where s.ownerUser.id = :userId
          and s.deletedAt is null
          and upper(s.title) not like 'AUTO:%'
        order by s.createdAt desc
        """
    )
    fun findVisibleUserSets(@Param("userId") userId: Long): List<QaQuestionSet>

    fun findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
        visibility: QuestionSetVisibility,
        status: QuestionSetStatus
    ): List<QaQuestionSet>

    fun findAllByDeletedAtIsNullOrderByCreatedAtDesc(): List<QaQuestionSet>
}
