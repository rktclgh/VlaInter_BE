package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentExamQuestion
import org.springframework.data.jpa.repository.JpaRepository

interface StudentExamQuestionRepository : JpaRepository<StudentExamQuestion, Long> {
    fun findAllBySessionIdInOrderBySessionIdAscQuestionOrderAsc(sessionIds: Collection<Long>): List<StudentExamQuestion>
    fun findAllBySessionIdOrderByQuestionOrderAsc(sessionId: Long): List<StudentExamQuestion>
}
