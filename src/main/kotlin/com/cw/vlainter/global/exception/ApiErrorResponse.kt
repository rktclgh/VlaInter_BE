package com.cw.vlainter.global.exception

import java.time.OffsetDateTime

data class ApiErrorResponse(
    val timestamp: String = OffsetDateTime.now().toString(),
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val errors: List<ApiFieldError> = emptyList()
)

data class ApiFieldError(
    val field: String,
    val message: String
)
