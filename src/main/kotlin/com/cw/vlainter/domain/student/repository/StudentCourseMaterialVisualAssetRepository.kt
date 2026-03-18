package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourseMaterialVisualAsset
import org.springframework.data.jpa.repository.JpaRepository

interface StudentCourseMaterialVisualAssetRepository : JpaRepository<StudentCourseMaterialVisualAsset, Long> {
    fun findAllByMaterial_IdOrderByAssetOrderAsc(materialId: Long): List<StudentCourseMaterialVisualAsset>
    fun deleteAllByMaterial_Id(materialId: Long)
    fun deleteAllByUserFile_Id(userFileId: Long)
}
