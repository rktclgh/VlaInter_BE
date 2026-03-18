package com.cw.vlainter.domain.academic.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.cw.vlainter.domain.academic.dto.DepartmentSearchItemResponse
import com.cw.vlainter.domain.academic.dto.UniversitySearchItemResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class AcademicMetadataImportRunner(
    private val objectMapper: ObjectMapper,
    private val academicSearchService: AcademicSearchService
) : ApplicationRunner {
    @Value("\${academic.metadata-import.enabled:false}")
    private var enabled: Boolean = false

    @Value("\${academic.metadata-import.json-path:}")
    private lateinit var jsonPath: String

    @Value("\${academic.metadata-import.targets:}")
    private lateinit var rawTargets: String

    @Value("\${academic.metadata-import.mode:import}")
    private lateinit var mode: String

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!enabled) return

        val targets = parseTargets(rawTargets)
        require(targets.isNotEmpty()) { "academic.metadata-import.targets is required" }

        if (mode.equals("cleanup", ignoreCase = true)) {
            val summaries = targets.map { cleanupTarget(it) }
            summaries.forEach { summary ->
                logger.info(
                    "학과 메타데이터 cleanup school={} queryMajor={} deletedDepartments={} deletedUniversity={}",
                    summary.target.schoolName,
                    summary.target.majorName,
                    summary.deletedDepartmentsCount,
                    summary.deletedUniversity
                )
            }
            return
        }

        val path = jsonPath.trim()
        require(path.isNotEmpty()) { "academic.metadata-import.json-path is required" }

        val records = loadRecords(Path.of(path))
        val summaries = targets.map { importTarget(records, it) }

        summaries.forEach { summary ->
            when {
                summary.importedDepartments.isNotEmpty() -> {
                    logger.info(
                        "학과 메타데이터 import 완료 school={} queryMajor={} imported={}",
                        summary.target.schoolName,
                        summary.target.majorName,
                        summary.importedDepartments.joinToString()
                    )
                }

                summary.candidateDepartments.isNotEmpty() -> {
                    logger.info(
                        "학과 메타데이터 import 후보만 발견 school={} queryMajor={} candidates={}",
                        summary.target.schoolName,
                        summary.target.majorName,
                        summary.candidateDepartments.joinToString()
                    )
                }

                else -> {
                    logger.warn(
                        "학과 메타데이터 미발견 school={} queryMajor={}",
                        summary.target.schoolName,
                        summary.target.majorName
                    )
                }
            }
        }
    }

    private fun loadRecords(path: Path): List<JsonNode> {
        require(Files.exists(path)) { "학과 메타데이터 파일을 찾을 수 없습니다: $path" }
        Files.newBufferedReader(path).use { reader ->
            val root = objectMapper.readTree(reader)
            return root.path("records")
                .takeIf { it.isArray }
                ?.toList()
                .orEmpty()
        }
    }

    private fun importTarget(records: List<JsonNode>, target: ImportTarget): ImportSummary {
        val schoolMatches = records.filter { record ->
            normalize(record.path("학교명").asText()) == target.normalizedSchoolName
        }
        if (schoolMatches.isEmpty()) {
            return ImportSummary(target = target)
        }

        val exactDepartmentMatches = schoolMatches.filter { record ->
            normalize(record.path("학과명").asText()) == target.normalizedMajorName
        }
        val selectedMatches = if (exactDepartmentMatches.isNotEmpty()) {
            exactDepartmentMatches
        } else {
            schoolMatches.filter { record ->
                normalize(record.path("학과명").asText()).contains(target.normalizedMajorName)
            }
        }

        val uniqueDepartments = linkedSetOf<String>()
        selectedMatches.forEach { uniqueDepartments += it.path("학과명").asText().trim() }
        if (uniqueDepartments.isEmpty()) {
            return ImportSummary(target = target)
        }

        val universityName = selectedMatches.first().path("학교명").asText().trim()
        val university = academicSearchService.upsertUniversities(
            listOf(
                UniversitySearchItemResponse(
                    universityName = universityName
                )
            )
        ).first()

        val importedDepartments = academicSearchService.upsertDepartments(
            university = university,
            items = uniqueDepartments.map { departmentName ->
                DepartmentSearchItemResponse(
                    universityName = universityName,
                    departmentName = departmentName
                )
            }
        ).map { it.name }

        return ImportSummary(
            target = target,
            candidateDepartments = uniqueDepartments.toList(),
            importedDepartments = importedDepartments
        )
    }

    private fun cleanupTarget(target: ImportTarget): CleanupSummary {
        val deletedDepartments = when {
            target.normalizedSchoolName == normalize("대림대학교") && target.normalizedMajorName == normalize("자동차") ->
                academicSearchService.deleteDepartmentsByExactNames(
                    universityName = "대림대학교",
                    departmentNames = listOf(
                        "미래자동차공학부",
                        "미래자동차공학과",
                        "미래자동차학부",
                        "미래자동차과",
                        "자동차학부",
                        "자동차과",
                        "자동차공학과(1년)",
                        "자동차공학과"
                    )
                )

            target.normalizedSchoolName == normalize("아주대학교") && target.normalizedMajorName == normalize("경영학과") ->
                academicSearchService.deleteDepartmentsByExactNames(
                    universityName = "아주대학교",
                    departmentNames = listOf("경영학과")
                )

            else -> 0
        }

        val deletedUniversity = academicSearchService.deleteUniversityIfEmpty(target.schoolName)
        return CleanupSummary(
            target = target,
            deletedDepartmentsCount = deletedDepartments,
            deletedUniversity = deletedUniversity
        )
    }

    private fun parseTargets(value: String): List<ImportTarget> {
        return value.split("|")
            .mapNotNull { token ->
                val parts = token.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val schoolName = parts[0].trim()
                val majorName = parts[1].trim()
                if (schoolName.isEmpty() || majorName.isEmpty()) return@mapNotNull null
                ImportTarget(
                    schoolName = schoolName,
                    majorName = majorName,
                    normalizedSchoolName = normalize(schoolName),
                    normalizedMajorName = normalize(majorName)
                )
            }
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace("\\s+".toRegex(), "")
            .trim()
    }

    private data class ImportTarget(
        val schoolName: String,
        val majorName: String,
        val normalizedSchoolName: String,
        val normalizedMajorName: String
    )

    private data class ImportSummary(
        val target: ImportTarget,
        val candidateDepartments: List<String> = emptyList(),
        val importedDepartments: List<String> = emptyList()
    )

    private data class CleanupSummary(
        val target: ImportTarget,
        val deletedDepartmentsCount: Int = 0,
        val deletedUniversity: Boolean = false
    )
}
