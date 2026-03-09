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
        select s
        from QaQuestionSet s
        where s.ownerUser.id = :userId
          and s.ownerType = com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType.USER
          and s.deletedAt is null
        order by s.createdAt desc
        """
    )
    fun findVisibleUserSets(@Param("userId") userId: Long): List<QaQuestionSet>

    fun findFirstByOwnerUser_IdAndOwnerTypeAndVisibilityAndJobNameAndSkillNameAndDescriptionAndDeletedAtIsNullOrderByCreatedAtDesc(
        userId: Long,
        ownerType: com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType,
        visibility: QuestionSetVisibility,
        jobName: String,
        skillName: String,
        description: String
    ): QaQuestionSet?

    fun findAllByVisibilityAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
        visibility: QuestionSetVisibility,
        status: QuestionSetStatus
    ): List<QaQuestionSet>

    fun findAllByDeletedAtIsNullOrderByCreatedAtDesc(): List<QaQuestionSet>
}
