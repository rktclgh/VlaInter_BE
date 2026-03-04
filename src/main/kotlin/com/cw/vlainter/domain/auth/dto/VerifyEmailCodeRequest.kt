package com.cw.vlainter.domain.auth.dto

data class VerifyEmailCodeRequest(
    val email: String,
    val code: String
)
