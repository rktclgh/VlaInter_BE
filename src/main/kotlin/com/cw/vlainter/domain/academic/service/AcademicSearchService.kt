package com.cw.vlainter.domain.academic.service

import com.cw.vlainter.domain.academic.dto.DepartmentSearchItemResponse
import com.cw.vlainter.domain.academic.dto.UniversitySearchItemResponse
import com.cw.vlainter.domain.academic.entity.AcademicDepartment
import com.cw.vlainter.domain.academic.entity.AcademicUniversity
import com.cw.vlainter.domain.academic.repository.AcademicDepartmentRepository
import com.cw.vlainter.domain.academic.repository.AcademicUniversityRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

@Service
class AcademicSearchService(
    restClientBuilder: RestClient.Builder,
    private val academicUniversityRepository: AcademicUniversityRepository,
    private val academicDepartmentRepository: AcademicDepartmentRepository,
    @Value("\${academyinfo.api.base-url:https://openapi.academyinfo.go.kr/openapi/service/rest}") private val academyInfoBaseUrl: String,
    @Value("\${academyinfo.api.service-key:}") private val academyInfoServiceKey: String,
    @Value("\${academyinfo.api.university-path:/BasicInformationService/getUniversityCode}") private val universitySearchPath: String,
    @Value("\${academyinfo.api.department-path:/SchoolMajorInfoService/getSchoolMajorInfo}") private val departmentSearchPath: String,
    @Value("\${academyinfo.api.survey-year:2024}") private val surveyYear: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient = restClientBuilder.baseUrl(academyInfoBaseUrl).build()

    fun searchUniversities(keyword: String): List<UniversitySearchItemResponse> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.length < MIN_QUERY_LENGTH) return emptyList()

        val localResults = academicUniversityRepository.searchByKeyword(normalizeKeyword(normalizedKeyword))
            .map { it.toResponse() }
            .take(MAX_RESULT_SIZE)
        if (localResults.isNotEmpty()) return localResults
        if (academyInfoServiceKey.isBlank()) return emptyList()

        val responseBody = requestUniversitySearch(normalizedKeyword) ?: return emptyList()
        val parsedItems = parseItems(responseBody)
        val fetchedItems = parsedItems
            .mapNotNull { item ->
                val universityName = item.valueOf("schlKrnNm").ifBlank { item.valueOf("schoolName") }
                if (universityName.isBlank()) return@mapNotNull null
                UniversitySearchItemResponse(
                    universityName = universityName,
                    universityCode = item.valueOf("schlId").ifBlank { item.valueOf("schoolCode") }.takeIf { it.isNotBlank() }
                )
            }
            .filter { it.universityName.contains(normalizedKeyword, ignoreCase = true) }
            .distinctBy { it.universityName }
            .take(MAX_RESULT_SIZE)
        return upsertUniversities(fetchedItems).map { it.toResponse() }
    }

    fun searchDepartments(universityId: Long?, universityName: String, keyword: String): List<DepartmentSearchItemResponse> {
        val normalizedUniversityName = universityName.trim()
        val normalizedKeyword = keyword.trim()
        if (
            normalizedUniversityName.length < MIN_QUERY_LENGTH ||
            normalizedKeyword.length < MIN_QUERY_LENGTH
        ) {
            return emptyList()
        }

        val localUniversity = resolveUniversityEntity(universityId, normalizedUniversityName)
        if (localUniversity != null) {
            val localResults = academicDepartmentRepository.searchByUniversityAndKeyword(
                universityId = localUniversity.id,
                keyword = normalizeKeyword(normalizedKeyword),
                pageable = PageRequest.of(0, MAX_RESULT_SIZE)
            ).map { it.toResponse() }
            if (localResults.isNotEmpty()) return localResults
        }
        if (academyInfoServiceKey.isBlank()) return emptyList()

        val normalizedUniversityKey = normalizeKeyword(normalizedUniversityName)
        val parsedItems = requestDepartmentSearch(
            universityCode = localUniversity?.externalCode,
            universityName = normalizedUniversityName,
            normalizedUniversityName = normalizedUniversityKey,
            departmentKeyword = normalizedKeyword
        )
        if (parsedItems.isEmpty()) return emptyList()
        val matchingUniversityItems = parsedItems.filter { item ->
            item.matchesUniversity(
                expectedUniversityCode = localUniversity?.externalCode,
                normalizedUniversityName = normalizedUniversityKey
            )
        }
        val university = localUniversity ?: matchingUniversityItems.firstOrNull()
            ?.let { item ->
                upsertUniversities(
                    listOf(
                        UniversitySearchItemResponse(
                            universityName = item.departmentUniversityName().ifBlank { normalizedUniversityName },
                            universityCode = item.departmentUniversityCode()
                        )
                    )
                ).firstOrNull()
            }
            ?: return emptyList()

        val fetchedItems = matchingUniversityItems
            .mapNotNull { item ->
                val departmentName = item.valueOf("korMjrNm").ifBlank { item.valueOf("majorNm") }
                val responseUniversityName = item.valueOf("korSchlNm")
                    .ifBlank { item.valueOf("schlKrnNm") }
                    .ifBlank { normalizedUniversityName }
                if (departmentName.isBlank() || responseUniversityName.isBlank()) return@mapNotNull null
                DepartmentSearchItemResponse(
                    universityName = responseUniversityName,
                    departmentName = departmentName,
                    departmentCode = item.valueOf("kediMjrId").ifBlank {
                        item.valueOf("kediMjrCd").ifBlank { item.valueOf("mjrCd") }
                    }.takeIf { it.isNotBlank() }
                )
            }
            .filter { it.departmentName.contains(normalizedKeyword, ignoreCase = true) }
            .distinctBy { "${it.universityName}|${it.departmentName}" }
            .take(MAX_RESULT_SIZE)
        return upsertDepartments(university, fetchedItems).map { it.toResponse() }
    }

    fun resolveVerifiedUniversity(universityId: Long, universityName: String): UniversitySearchItemResponse? {
        val entity = academicUniversityRepository.findById(universityId).orElse(null) ?: return null
        if (entity.name != universityName.trim()) return null
        return entity.toResponse()
    }

    fun resolveVerifiedDepartment(
        universityId: Long,
        departmentId: Long,
        departmentName: String,
        universityName: String
    ): DepartmentSearchItemResponse? {
        val normalizedDepartmentName = departmentName.trim()
        val entity = academicDepartmentRepository.findById(departmentId).orElse(null) ?: return null
        if (entity.university.id != universityId) return null
        if (entity.university.name != universityName.trim()) return null
        if (entity.name != normalizedDepartmentName) return null
        return entity.toResponse()
    }

    fun upsertUniversities(items: List<UniversitySearchItemResponse>): List<AcademicUniversity> {
        if (items.isEmpty()) return emptyList()
        val now = OffsetDateTime.now()
        return items.map { item ->
            val normalizedName = normalizeKeyword(item.universityName)
            val existing = item.universityCode?.let { academicUniversityRepository.findByExternalCode(it) }
                ?: academicUniversityRepository.findByNormalizedName(normalizedName)
            val target = existing ?: AcademicUniversity(
                externalCode = item.universityCode,
                name = item.universityName,
                normalizedName = normalizedName
            )
            target.externalCode = item.universityCode ?: target.externalCode
            target.name = item.universityName
            target.normalizedName = normalizedName
            target.lastSyncedAt = now
            academicUniversityRepository.save(target)
        }
    }

    fun upsertDepartments(
        university: AcademicUniversity,
        items: List<DepartmentSearchItemResponse>
    ): List<AcademicDepartment> {
        if (items.isEmpty()) return emptyList()
        val now = OffsetDateTime.now()
        return items.map { item ->
            val normalizedName = normalizeKeyword(item.departmentName)
            val existing = item.departmentCode?.let { academicDepartmentRepository.findByUniversityIdAndExternalCode(university.id, it) }
                ?: academicDepartmentRepository.findByUniversityIdAndNormalizedName(university.id, normalizedName)
            val target = existing ?: AcademicDepartment(
                university = university,
                externalCode = item.departmentCode,
                name = item.departmentName,
                normalizedName = normalizedName
            )
            target.university = university
            target.externalCode = item.departmentCode ?: target.externalCode
            target.name = item.departmentName
            target.normalizedName = normalizedName
            target.lastSyncedAt = now
            academicDepartmentRepository.save(target)
        }
    }

    private fun parseItems(xmlBody: String): List<XmlItemNode> {
        if (xmlBody.isBlank()) return emptyList()
        return runCatching {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            documentBuilderFactory.isNamespaceAware = false
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            documentBuilderFactory.isXIncludeAware = false
            documentBuilderFactory.isExpandEntityReferences = false
            val documentBuilder = documentBuilderFactory.newDocumentBuilder()
            val document = documentBuilder.parse(ByteArrayInputStream(xmlBody.toByteArray(Charsets.UTF_8)))
            val itemNodes = document.getElementsByTagName("item")
            buildList {
                for (index in 0 until itemNodes.length) {
                    val node = itemNodes.item(index)
                    if (node != null) add(XmlItemNode(node))
                }
            }
        }.onFailure { ex ->
            logger.warn("대학교/학과 XML 응답 파싱 실패", ex)
        }.getOrElse { emptyList() }
    }

    private fun requestUniversitySearch(keyword: String): String? {
        return runCatching {
            restClient.get()
                .uri { builder ->
                    builder.path(universitySearchPath)
                        .queryParam("serviceKey", academyInfoServiceKey)
                        .queryParam("pageNo", 1)
                        .queryParam("numOfRows", MAX_RESULT_SIZE)
                        .queryParam("svyYr", surveyYear)
                        .queryParam("schlKrnNm", keyword)
                        .build()
                }
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }.onFailure { ex ->
            logger.warn("대학교 검색 API 호출 실패 keyword={}", keyword, ex)
        }.getOrElse { null }
    }

    private fun requestDepartmentSearch(
        universityCode: String?,
        universityName: String,
        normalizedUniversityName: String,
        departmentKeyword: String
    ): List<XmlItemNode> {
        val primaryItems = requestDepartmentSearchFromPath(
            path = departmentSearchPath,
            universityCode = universityCode,
            universityName = universityName,
            normalizedUniversityName = normalizedUniversityName,
            departmentKeyword = departmentKeyword,
            fallback = false
        )
        if (
            countMatchingDepartmentItems(primaryItems, universityCode, normalizedUniversityName, departmentKeyword) > 0 ||
            departmentSearchPath == FALLBACK_DEPARTMENT_SEARCH_PATH
        ) {
            return primaryItems
        }
        return requestDepartmentSearchFromPath(
            path = FALLBACK_DEPARTMENT_SEARCH_PATH,
            universityCode = universityCode,
            universityName = universityName,
            normalizedUniversityName = normalizedUniversityName,
            departmentKeyword = departmentKeyword,
            fallback = true
        )
    }

    private fun requestDepartmentSearchFromPath(
        path: String,
        universityCode: String?,
        universityName: String,
        normalizedUniversityName: String,
        departmentKeyword: String,
        fallback: Boolean
    ): List<XmlItemNode> {
        val collected = mutableListOf<XmlItemNode>()
        var pageNo = 1
        while (pageNo <= MAX_REMOTE_SEARCH_PAGES) {
            val responseBody = runCatching {
                restClient.get()
                    .uri { builder ->
                        val uriBuilder = builder.path(path)
                            .queryParam("serviceKey", academyInfoServiceKey)
                            .queryParam("pageNo", pageNo)
                            .queryParam("numOfRows", REMOTE_DEPARTMENT_PAGE_SIZE)
                            .queryParam("svyYr", surveyYear)
                            .queryParam("schlKrnNm", universityName)
                        if (!universityCode.isNullOrBlank()) {
                            uriBuilder.queryParam("schlId", universityCode)
                        }
                        uriBuilder.build()
                    }
                    .retrieve()
                    .body(String::class.java)
                    .orEmpty()
            }.onFailure { ex ->
                if (fallback) {
                    logger.warn("학과 검색 API fallback 호출 실패 university={} universityCode={} page={}", universityName, universityCode, pageNo, ex)
                } else {
                    logger.warn("학과 검색 API 호출 실패 university={} universityCode={} page={}", universityName, universityCode, pageNo, ex)
                }
            }.getOrElse { null } ?: break

            val pageItems = parseItems(responseBody)
            if (pageItems.isEmpty()) break
            collected += pageItems
            if (countMatchingDepartmentItems(collected, universityCode, normalizedUniversityName, departmentKeyword) >= MAX_RESULT_SIZE) {
                break
            }
            if (pageItems.size < REMOTE_DEPARTMENT_PAGE_SIZE) break
            pageNo += 1
        }
        return collected
    }

    private fun resolveUniversityEntity(universityId: Long?, universityName: String): AcademicUniversity? {
        val normalizedUniversityName = normalizeKeyword(universityName)
        if (universityId != null) {
            val byId = academicUniversityRepository.findById(universityId).orElse(null)
            if (byId != null && normalizeKeyword(byId.name) == normalizedUniversityName) {
                return byId
            }
        }
        return academicUniversityRepository.findByNormalizedName(normalizedUniversityName)
    }

    private fun AcademicUniversity.toResponse(): UniversitySearchItemResponse {
        return UniversitySearchItemResponse(
            universityId = id,
            universityName = name,
            universityCode = externalCode
        )
    }

    private fun AcademicDepartment.toResponse(): DepartmentSearchItemResponse {
        return DepartmentSearchItemResponse(
            departmentId = id,
            universityId = university.id,
            universityName = university.name,
            departmentName = name,
            departmentCode = externalCode
        )
    }

    private fun normalizeKeyword(value: String): String {
        return value.trim().lowercase()
    }

    private fun XmlItemNode.departmentUniversityCode(): String? {
        return valueOf("schlId")
            .ifBlank { valueOf("schoolCode") }
            .takeIf { it.isNotBlank() }
    }

    private fun XmlItemNode.departmentUniversityName(): String {
        return valueOf("korSchlNm")
            .ifBlank { valueOf("schlKrnNm") }
            .ifBlank { valueOf("schoolName") }
    }

    private fun XmlItemNode.matchesUniversity(expectedUniversityCode: String?, normalizedUniversityName: String): Boolean {
        val responseUniversityCode = departmentUniversityCode()
        if (!expectedUniversityCode.isNullOrBlank() && !responseUniversityCode.isNullOrBlank()) {
            return responseUniversityCode == expectedUniversityCode
        }
        val responseUniversityName = departmentUniversityName()
        return responseUniversityName.isNotBlank() && normalizeKeyword(responseUniversityName) == normalizedUniversityName
    }

    private fun countMatchingDepartmentItems(
        items: List<XmlItemNode>,
        expectedUniversityCode: String?,
        normalizedUniversityName: String,
        departmentKeyword: String
    ): Int {
        return items.asSequence()
            .filter { item -> item.matchesUniversity(expectedUniversityCode, normalizedUniversityName) }
            .mapNotNull { item -> item.valueOf("korMjrNm").ifBlank { item.valueOf("majorNm") }.takeIf { it.isNotBlank() } }
            .filter { departmentName -> departmentName.contains(departmentKeyword, ignoreCase = true) }
            .distinct()
            .count()
    }

    private class XmlItemNode(private val node: org.w3c.dom.Node) {
        fun valueOf(tagName: String): String {
            val childNodes = node.childNodes ?: return ""
            for (index in 0 until childNodes.length) {
                val child = childNodes.item(index) ?: continue
                if (child.nodeName == tagName) {
                    return child.textContent?.trim().orEmpty()
                }
            }
            return ""
        }
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULT_SIZE = 20
        private const val REMOTE_DEPARTMENT_PAGE_SIZE = 50
        private const val MAX_REMOTE_SEARCH_PAGES = 5
        private const val FALLBACK_DEPARTMENT_SEARCH_PATH = "/BasicInformationService/getUniversityMajorCode"
    }
}
