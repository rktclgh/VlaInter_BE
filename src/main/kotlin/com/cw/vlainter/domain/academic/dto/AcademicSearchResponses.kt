package com.cw.vlainter.domain.academic.dto

data class UniversitySearchItemResponse(
    val universityId: Long? = null,
    val universityName: String,
    val universityCode: String? = null
)

data class DepartmentSearchItemResponse(
    val departmentId: Long? = null,
    val universityId: Long? = null,
    val universityName: String,
    val departmentName: String,
    val departmentCode: String? = null
)
