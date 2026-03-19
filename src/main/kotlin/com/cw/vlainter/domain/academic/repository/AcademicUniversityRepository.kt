package com.cw.vlainter.domain.academic.repository

import com.cw.vlainter.domain.academic.entity.AcademicUniversity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AcademicUniversityRepository : JpaRepository<AcademicUniversity, Long> {
    fun findByExternalCode(externalCode: String): AcademicUniversity?
    fun findByNormalizedName(normalizedName: String): AcademicUniversity?
    fun findAllByNormalizedNameIn(normalizedNames: Collection<String>): List<AcademicUniversity>

    @Query(
        """
        select u
        from AcademicUniversity u
        where u.normalizedName like concat('%', :keyword, '%')
        order by u.name asc
        """
    )
    fun searchByKeyword(@Param("keyword") keyword: String): List<AcademicUniversity>
}
