package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentWrongAnswerSet
import org.springframework.data.jpa.repository.JpaRepository

interface StudentWrongAnswerSetRepository : JpaRepository<StudentWrongAnswerSet, Long> {
    fun findByIdAndUserId(id: Long, userId: Long): StudentWrongAnswerSet?
    fun findAllByCourseIdAndUserIdOrderByUpdatedAtDesc(courseId: Long, userId: Long): List<StudentWrongAnswerSet>
    fun findByRetestSessionIdAndUserId(retestSessionId: Long, userId: Long): StudentWrongAnswerSet?
}
