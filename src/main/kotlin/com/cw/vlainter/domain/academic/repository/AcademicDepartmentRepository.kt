package com.cw.vlainter.domain.academic.repository

import com.cw.vlainter.domain.academic.entity.AcademicDepartment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AcademicDepartmentRepository : JpaRepository<AcademicDepartment, Long> {
    fun findByUniversityIdAndExternalCode(universityId: Long, externalCode: String): AcademicDepartment?
    fun findByUniversityIdAndNormalizedName(universityId: Long, normalizedName: String): AcademicDepartment?

    @Query(
        """
        select d
        from AcademicDepartment d
        where d.university.id = :universityId
          and d.normalizedName like concat('%', :keyword, '%')
        order by d.name asc
        """
    )
    fun searchByUniversityAndKeyword(
        @Param("universityId") universityId: Long,
        @Param("keyword") keyword: String,
        pageable: Pageable
    ): List<AcademicDepartment>
}
