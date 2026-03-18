package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentWrongAnswerItem
import org.springframework.data.jpa.repository.JpaRepository

interface StudentWrongAnswerItemRepository : JpaRepository<StudentWrongAnswerItem, Long> {
    fun deleteAllBySetId(setId: Long)
    fun findAllBySetIdOrderByQuestionOrderAsc(setId: Long): List<StudentWrongAnswerItem>
    fun findAllBySetIdInOrderBySetIdAscQuestionOrderAsc(setIds: Collection<Long>): List<StudentWrongAnswerItem>
}
