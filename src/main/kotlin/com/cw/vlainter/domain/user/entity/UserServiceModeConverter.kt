package com.cw.vlainter.domain.user.entity

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory
import java.util.Locale

@Converter(autoApply = false)
class UserServiceModeConverter : AttributeConverter<UserServiceMode?, String?> {

    override fun convertToDatabaseColumn(attribute: UserServiceMode?): String? = attribute?.name

    override fun convertToEntityAttribute(dbData: String?): UserServiceMode? {
        val normalized = dbData
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.replace(Regex("[^A-Z0-9]+"), "_")
            ?.trim('_')
            .orEmpty()

        if (normalized.isBlank()) {
            return null
        }

        return when (normalized) {
            UserServiceMode.JOB_SEEKER.name,
            "JOBSEEKER" -> UserServiceMode.JOB_SEEKER

            UserServiceMode.STUDENT.name,
            "STUDENTMODE",
            "COLLEGE_STUDENT",
            "UNIVERSITY_STUDENT" -> UserServiceMode.STUDENT

            else -> {
                logger.warn("Unknown user service mode value detected. dbData={}", dbData)
                null
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserServiceModeConverter::class.java)
    }
}
