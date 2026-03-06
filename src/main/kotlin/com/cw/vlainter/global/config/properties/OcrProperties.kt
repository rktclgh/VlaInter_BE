package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ocr")
data class OcrProperties(
    val enabled: Boolean = true,
    val tesseractCommand: String = "tesseract",
    val languages: String = "kor+eng",
    val renderDpi: Float = 200f,
    val fallbackMinTextLength: Int = 200,
    val timeoutSeconds: Long = 20
)
