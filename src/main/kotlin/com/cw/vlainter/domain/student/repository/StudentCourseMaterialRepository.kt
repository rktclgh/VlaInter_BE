package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudentCourseMaterialRepository : JpaRepository<StudentCourseMaterial, Long> {
    fun findAllByCourse_IdOrderByCreatedAtDesc(courseId: Long): List<StudentCourseMaterial>
    fun countByCourse_Id(courseId: Long): Long
    fun findByUserFile_Id(userFileId: Long): StudentCourseMaterial?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StudentCourseMaterial material where material.userFile.id = :userFileId")
    fun deleteAllByUserFileId(@Param("userFileId") userFileId: Long): Int
}
