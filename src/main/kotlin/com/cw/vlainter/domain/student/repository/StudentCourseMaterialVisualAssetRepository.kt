package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourseMaterialVisualAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudentCourseMaterialVisualAssetRepository : JpaRepository<StudentCourseMaterialVisualAsset, Long> {
    fun findAllByMaterial_IdOrderByAssetOrderAsc(materialId: Long): List<StudentCourseMaterialVisualAsset>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StudentCourseMaterialVisualAsset asset where asset.material.id = :materialId")
    fun deleteAllByMaterialId(@Param("materialId") materialId: Long): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StudentCourseMaterialVisualAsset asset where asset.userFile.id = :userFileId")
    fun deleteAllByUserFileId(@Param("userFileId") userFileId: Long): Int
}
