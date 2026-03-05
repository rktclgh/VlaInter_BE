package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.storage.s3")
data class S3Properties(
    val bucket: String = "",
    val region: String = "ap-northeast-2",
    val endpoint: String = "",
    val keyPrefix: String = "uploads/final/user-files",
    val maxFileSizeBytes: Long = 20 * 1024 * 1024
)
