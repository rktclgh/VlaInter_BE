package com.cw.vlainter.domain.auth.dto

data class SendTemporaryPasswordRequest(
    val email: String,
    val name: String
)
