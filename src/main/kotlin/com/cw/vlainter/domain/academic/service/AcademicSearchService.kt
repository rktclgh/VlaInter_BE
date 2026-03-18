package com.cw.vlainter.domain.academic.service

import com.cw.vlainter.domain.academic.dto.DepartmentSearchItemResponse
import com.cw.vlainter.domain.academic.dto.UniversitySearchItemResponse
import com.cw.vlainter.domain.academic.entity.AcademicDepartment
import com.cw.vlainter.domain.academic.entity.AcademicUniversity
import com.cw.vlainter.domain.academic.repository.AcademicDepartmentRepository
import com.cw.vlainter.domain.academic.repository.AcademicUniversityRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

@Service
class AcademicSearchService(
    restClientBuilder: RestClient.Builder,
    private val academicUniversityRepository: AcademicUniversityRepository,
    private val academicDepartmentRepository: AcademicDepartmentRepository,
    private val selfProvider: ObjectProvider<AcademicSearchService>,
    @Value("\${academyinfo.api.base-url:http://api.data.go.kr}") private val academyInfoBaseUrl: String,
    @Value("\${academyinfo.api.service-key:}") private val academyInfoServiceKey: String,
    @Value("\${academyinfo.api.university-path:/openapi/tn_pubr_public_univ_major_api}") private val universitySearchPath: String,
    @Value("\${academyinfo.api.department-path:/openapi/tn_pubr_public_univ_major_api}") private val departmentSearchPath: String,
    @Value("\${academyinfo.api.survey-year:2024}") private val surveyYear: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient = restClientBuilder
        .baseUrl(academyInfoBaseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(5_000)
                setReadTimeout(5_000)
            }
        )
        .build()
    private val objectMapper = jacksonObjectMapper()
    private val inFlightWarmKeys = ConcurrentHashMap.newKeySet<String>()

    @Transactional
    fun searchUniversities(keyword: String): List<UniversitySearchItemResponse> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.length < MIN_QUERY_LENGTH) return emptyList()

        val localResults = academicUniversityRepository.searchByKeyword(normalizeKeyword(normalizedKeyword))
            .map { it.toResponse() }
            .take(MAX_RESULT_SIZE)
        if (localResults.isNotEmpty() || academyInfoServiceKey.isBlank()) return localResults

        val quickRemoteCandidates = requestUniversitySearch(
            keyword = normalizedKeyword,
            maxPages = QUICK_REMOTE_UNIVERSITY_PAGES
        )
            .mapNotNull { item -> item.toUniversityResponse() }
            .distinctBy { normalizeKeyword(it.universityName) }
        val remoteResults = upsertUniversities(quickRemoteCandidates)
            .map { it.toResponse() }
            .filter { it.universityName.contains(normalizedKeyword, ignoreCase = true) }
            .take(MAX_RESULT_SIZE)
        triggerUniversityWarmAsync(normalizedKeyword)
        return mergeUniversityResults(localResults, remoteResults)
    }

    @Transactional
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
        val localResults = if (localUniversity != null) {
            academicDepartmentRepository.searchByUniversityAndKeyword(
                universityId = localUniversity.id,
                keyword = normalizeKeyword(normalizedKeyword),
                pageable = PageRequest.of(0, MAX_RESULT_SIZE)
            ).map { it.toResponse() }
        } else {
            emptyList()
        }
        if (localResults.isNotEmpty() || academyInfoServiceKey.isBlank()) return localResults

        val parsedItems = requestDepartmentSearch(
            universityName = normalizedUniversityName,
            maxPages = QUICK_REMOTE_DEPARTMENT_PAGES
        )
        if (parsedItems.isEmpty()) return localResults

        val cachedUniversities = upsertUniversities(
            parsedItems.mapNotNull { item -> item.toUniversityResponse() }
                .distinctBy { normalizeKeyword(it.universityName) }
        )
        val universityByName = cachedUniversities.associateBy { normalizeKeyword(it.name) }
        val targetUniversity = localUniversity
            ?: universityByName[normalizeKeyword(normalizedUniversityName)]
            ?: upsertUniversities(
                listOf(UniversitySearchItemResponse(universityName = normalizedUniversityName))
            ).firstOrNull()
            ?: return localResults

        val quickDepartmentItems = parsedItems
            .mapNotNull { item ->
                val department = item.toDepartmentResponse() ?: return@mapNotNull null
                val responseUniversityName = item.valueOf("schlNm").ifBlank { normalizedUniversityName }
                val university = universityByName[normalizeKeyword(responseUniversityName)] ?: return@mapNotNull null
                if (university.id != targetUniversity.id) return@mapNotNull null
                if (!department.departmentName.contains(normalizedKeyword, ignoreCase = true)) return@mapNotNull null
                department
            }
            .distinctBy { normalizeKeyword(it.departmentName) }
            .take(MAX_RESULT_SIZE)

        val remoteResults = upsertDepartments(targetUniversity, quickDepartmentItems)
            .map { it.toResponse() }
        triggerDepartmentWarmAsync(normalizedUniversityName)
        return mergeDepartmentResults(localResults, remoteResults)
    }

    @Transactional(readOnly = true)
    fun resolveVerifiedUniversity(universityId: Long, universityName: String): UniversitySearchItemResponse? {
        val entity = academicUniversityRepository.findById(universityId).orElse(null) ?: return null
        if (entity.name != universityName.trim()) return null
        return entity.toResponse()
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    fun findUniversityByName(universityName: String): UniversitySearchItemResponse? {
        val normalizedUniversityName = universityName.trim().takeIf { it.isNotBlank() } ?: return null
        return academicUniversityRepository.findByNormalizedName(normalizeKeyword(normalizedUniversityName))?.toResponse()
    }

    @Transactional(readOnly = true)
    fun findDepartmentByName(universityId: Long, departmentName: String): DepartmentSearchItemResponse? {
        val normalizedDepartmentName = departmentName.trim().takeIf { it.isNotBlank() } ?: return null
        return academicDepartmentRepository.findByUniversityIdAndNormalizedName(
            universityId,
            normalizeKeyword(normalizedDepartmentName)
        )?.toResponse()
    }

    @Transactional
    fun resolveOrCreateUniversity(universityName: String, universityId: Long? = null): UniversitySearchItemResponse {
        val normalizedUniversityName = universityName.trim()
        require(normalizedUniversityName.isNotBlank()) { "universityName must not be blank" }

        if (universityId != null) {
            return resolveVerifiedUniversity(universityId, normalizedUniversityName)
                ?: throw IllegalArgumentException("검색 결과에서 선택한 대학교만 저장할 수 있습니다.")
        }

        val normalizedKey = normalizeKeyword(normalizedUniversityName)
        academicUniversityRepository.findByNormalizedName(normalizedKey)?.let { return it.toResponse() }

        val remoteMatch = searchUniversities(normalizedUniversityName)
            .firstOrNull { normalizeKeyword(it.universityName) == normalizedKey }
        if (remoteMatch != null) {
            return remoteMatch
        }

        return upsertUniversities(
            listOf(UniversitySearchItemResponse(universityName = normalizedUniversityName))
        ).first().toResponse()
    }

    @Transactional
    fun resolveOrCreateDepartment(
        university: UniversitySearchItemResponse,
        departmentName: String,
        departmentId: Long? = null
    ): DepartmentSearchItemResponse {
        val normalizedDepartmentName = departmentName.trim()
        require(normalizedDepartmentName.isNotBlank()) { "departmentName must not be blank" }

        val universityEntity = university.universityId
            ?.let { academicUniversityRepository.findById(it).orElse(null) }
            ?: academicUniversityRepository.findByNormalizedName(normalizeKeyword(university.universityName))
            ?: throw IllegalArgumentException("대학교 정보를 찾을 수 없습니다.")

        if (departmentId != null) {
            return resolveVerifiedDepartment(
                universityId = universityEntity.id,
                departmentId = departmentId,
                departmentName = normalizedDepartmentName,
                universityName = universityEntity.name
            ) ?: throw IllegalArgumentException("검색 결과에서 선택한 학과만 저장할 수 있습니다.")
        }

        val normalizedKey = normalizeKeyword(normalizedDepartmentName)
        academicDepartmentRepository.findByUniversityIdAndNormalizedName(universityEntity.id, normalizedKey)
            ?.let { return it.toResponse() }

        val remoteMatch = searchDepartments(
            universityId = universityEntity.id,
            universityName = universityEntity.name,
            keyword = normalizedDepartmentName
        ).firstOrNull { normalizeKeyword(it.departmentName) == normalizedKey }
        if (remoteMatch != null) {
            return remoteMatch
        }

        return upsertDepartments(
            university = universityEntity,
            items = listOf(
                DepartmentSearchItemResponse(
                    universityId = universityEntity.id,
                    universityName = universityEntity.name,
                    departmentName = normalizedDepartmentName
                )
            )
        ).first().toResponse()
    }

    @Transactional
    fun deleteDepartmentsByExactNames(universityName: String, departmentNames: Collection<String>): Int {
        val university = academicUniversityRepository.findByNormalizedName(normalizeKeyword(universityName)) ?: return 0
        val targets = academicDepartmentRepository.findAllByUniversityId(university.id)
            .filter { department -> departmentNames.any { normalizeKeyword(it) == department.normalizedName } }
        if (targets.isEmpty()) return 0
        academicDepartmentRepository.deleteAll(targets)
        return targets.size
    }

    @Transactional
    fun deleteUniversityIfEmpty(universityName: String): Boolean {
        val university = academicUniversityRepository.findByNormalizedName(normalizeKeyword(universityName)) ?: return false
        if (academicDepartmentRepository.findAllByUniversityId(university.id).isNotEmpty()) return false
        if (!university.externalCode.isNullOrBlank()) return false
        academicUniversityRepository.delete(university)
        return true
    }

    fun upsertUniversities(items: List<UniversitySearchItemResponse>): List<AcademicUniversity> {
        if (items.isEmpty()) return emptyList()
        val now = OffsetDateTime.now()
        return items.map { item ->
            val normalizedName = normalizeKeyword(item.universityName)
            val existingByName = academicUniversityRepository.findByNormalizedName(normalizedName)
            val existingByCode = item.universityCode?.let { academicUniversityRepository.findByExternalCode(it) }
            val target = existingByName ?: existingByCode ?: AcademicUniversity(
                externalCode = item.universityCode,
                name = item.universityName,
                normalizedName = normalizedName
            )
            val hasIdentityConflict = existingByName != null &&
                existingByCode != null &&
                existingByName.id != existingByCode.id

            if (hasIdentityConflict) {
                val nameMatchedUniversity = requireNotNull(existingByName)
                val codeMatchedUniversity = requireNotNull(existingByCode)
                logger.warn(
                    "대학교 캐시 중복 데이터 감지 normalizedName={} incomingCode={} nameId={} codeId={}",
                    normalizedName,
                    item.universityCode,
                    nameMatchedUniversity.id,
                    codeMatchedUniversity.id
                )
            }

            target.name = item.universityName
            target.normalizedName = normalizedName
            if (!item.universityCode.isNullOrBlank() && (existingByCode == null || existingByCode.id == target.id)) {
                target.externalCode = item.universityCode
            }
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
            val existingByName = academicDepartmentRepository.findByUniversityIdAndNormalizedName(university.id, normalizedName)
            val existingByCode = item.departmentCode?.let {
                academicDepartmentRepository.findByUniversityIdAndExternalCode(university.id, it)
            }
            val target = existingByName ?: existingByCode ?: AcademicDepartment(
                university = university,
                externalCode = item.departmentCode,
                name = item.departmentName,
                normalizedName = normalizedName
            )
            val hasIdentityConflict = existingByName != null &&
                existingByCode != null &&
                existingByName.id != existingByCode.id

            if (hasIdentityConflict) {
                val nameMatchedDepartment = requireNotNull(existingByName)
                val codeMatchedDepartment = requireNotNull(existingByCode)
                logger.warn(
                    "학과 캐시 중복 데이터 감지 universityId={} normalizedName={} incomingCode={} nameId={} codeId={}",
                    university.id,
                    normalizedName,
                    item.departmentCode,
                    nameMatchedDepartment.id,
                    codeMatchedDepartment.id
                )
            }

            target.university = university
            target.name = item.departmentName
            target.normalizedName = normalizedName
            if (!item.departmentCode.isNullOrBlank() && (existingByCode == null || existingByCode.id == target.id)) {
                target.externalCode = item.departmentCode
            }
            target.lastSyncedAt = now
            academicDepartmentRepository.save(target)
        }
    }

    private fun requestUniversitySearch(
        keyword: String,
        maxPages: Int = MAX_REMOTE_SEARCH_PAGES
    ): List<ApiItemNode> {
        return requestPublicUniversityMajorApi(
            path = universitySearchPath,
            pageSize = REMOTE_UNIVERSITY_PAGE_SIZE,
            maxPages = maxPages
        ) { uriBuilder ->
            uriBuilder.queryParam("SCHL_NM", keyword)
        }
    }

    private fun requestDepartmentSearch(
        universityName: String,
        maxPages: Int = MAX_REMOTE_SEARCH_PAGES
    ): List<ApiItemNode> {
        return requestPublicUniversityMajorApi(
            path = departmentSearchPath,
            pageSize = REMOTE_DEPARTMENT_PAGE_SIZE,
            maxPages = maxPages
        ) { uriBuilder ->
            uriBuilder.queryParam("SCHL_NM", universityName)
        }
    }

    private fun requestPublicUniversityMajorApi(
        path: String,
        pageSize: Int,
        maxPages: Int,
        extraParams: (org.springframework.web.util.UriBuilder) -> org.springframework.web.util.UriBuilder
    ): List<ApiItemNode> {
        val collected = mutableListOf<ApiItemNode>()
        var pageNo = 1
        while (pageNo <= maxPages) {
            val responseBody = runCatching {
                restClient.get()
                    .uri { builder ->
                        extraParams(
                            builder.path(path)
                                .queryParam("serviceKey", academyInfoServiceKey)
                                .queryParam("pageNo", pageNo)
                                .queryParam("numOfRows", pageSize)
                                .queryParam("type", "json")
                                .queryParam("YR", surveyYear)
                        ).build()
                    }
                    .retrieve()
                    .body(String::class.java)
                    .orEmpty()
            }.onFailure { ex ->
                logger.warn(
                    "대학교/학과 공공데이터 API 호출 실패 path={} page={} reason={}",
                    path,
                    pageNo,
                    ex.rootMessage()
                )
            }.getOrElse { null } ?: break

            val pageItems = parseItems(responseBody)
            if (pageItems.isEmpty()) break
            collected += pageItems
            if (pageItems.size < pageSize) break
            pageNo += 1
        }
        return collected
    }

    private fun parseItems(jsonBody: String): List<ApiItemNode> {
        if (jsonBody.isBlank()) return emptyList()
        val trimmedBody = jsonBody.trimStart()
        if (trimmedBody.startsWith("<")) {
            logger.warn("대학교/학과 공공데이터 API가 JSON 대신 HTML을 반환했습니다. baseUrl/path 설정을 확인하세요.")
            return emptyList()
        }
        return runCatching {
            val root = objectMapper.readTree(trimmedBody)
            val header = root.path("response").path("header")
            val resultCode = header.path("resultCode").asText()
            if (resultCode.isNotBlank() && resultCode != "00") {
                if (resultCode != "03") {
                    logger.warn(
                        "대학교/학과 공공데이터 API 비정상 응답 code={} message={}",
                        resultCode,
                        header.path("resultMsg").asText()
                    )
                }
                return emptyList()
            }
            val itemsNode = root.path("response").path("body").path("items")
            if (!itemsNode.isArray) return emptyList()
            itemsNode.map { node -> ApiItemNode(node) }
        }.onFailure { ex ->
            logger.warn("대학교/학과 JSON 응답 파싱 실패: {}", ex.message)
        }.getOrElse { emptyList() }
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

    private fun mergeUniversityResults(
        localResults: List<UniversitySearchItemResponse>,
        remoteResults: List<UniversitySearchItemResponse>
    ): List<UniversitySearchItemResponse> {
        return (localResults + remoteResults)
            .distinctBy { item ->
                item.universityId?.toString()
                    ?: item.universityCode
                    ?: normalizeKeyword(item.universityName)
            }
            .take(MAX_RESULT_SIZE)
    }

    private fun mergeDepartmentResults(
        localResults: List<DepartmentSearchItemResponse>,
        remoteResults: List<DepartmentSearchItemResponse>
    ): List<DepartmentSearchItemResponse> {
        return (localResults + remoteResults)
            .distinctBy { item ->
                item.departmentId?.toString()
                    ?: item.departmentCode
                    ?: "${item.universityId}:${normalizeKeyword(item.departmentName)}"
            }
            .take(MAX_RESULT_SIZE)
    }

    private class ApiItemNode(private val node: com.fasterxml.jackson.databind.JsonNode) {
        fun valueOf(tagName: String): String {
            return node.path(tagName).asText("").trim()
        }

        fun toUniversityResponse(): UniversitySearchItemResponse? {
            val universityName = valueOf("schlNm")
            if (universityName.isBlank()) return null
            return UniversitySearchItemResponse(
                universityName = universityName,
                universityCode = valueOf("insttCode").takeIf { it.isNotBlank() }
            )
        }

        fun toDepartmentResponse(): DepartmentSearchItemResponse? {
            val universityName = valueOf("schlNm")
            val departmentName = valueOf("scsbjtNm")
            if (universityName.isBlank() || departmentName.isBlank()) return null
            return DepartmentSearchItemResponse(
                universityName = universityName,
                departmentName = departmentName,
                departmentCode = valueOf("scsbjtCdNm").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun Throwable.rootMessage(): String {
        return generateSequence(this) { it.cause }
            .lastOrNull()
            ?.message
            ?.takeIf { it.isNotBlank() }
            ?: this.message.orEmpty()
    }

    private fun triggerUniversityWarmAsync(keyword: String) {
        val warmKey = "university:${normalizeKeyword(keyword)}"
        if (!inFlightWarmKeys.add(warmKey)) return
        selfProvider.getObject().warmUniversitySearchCacheAsync(keyword, warmKey)
    }

    private fun triggerDepartmentWarmAsync(universityName: String) {
        val warmKey = "department:${normalizeKeyword(universityName)}"
        if (!inFlightWarmKeys.add(warmKey)) return
        selfProvider.getObject().warmDepartmentSearchCacheAsync(universityName, warmKey)
    }

    @Async("academicSearchWarmExecutor")
    fun warmUniversitySearchCacheAsync(keyword: String, warmKey: String) {
        try {
            val remoteCandidates = requestUniversitySearch(keyword)
                .mapNotNull { item -> item.toUniversityResponse() }
                .distinctBy { normalizeKeyword(it.universityName) }
            if (remoteCandidates.isNotEmpty()) {
                upsertUniversities(remoteCandidates)
            }
        } catch (ex: Exception) {
            logger.warn("대학교 검색 캐시 워밍 실패 keyword={} reason={}", keyword, ex.rootMessage())
        } finally {
            inFlightWarmKeys.remove(warmKey)
        }
    }

    @Async("academicSearchWarmExecutor")
    fun warmDepartmentSearchCacheAsync(universityName: String, warmKey: String) {
        try {
            val parsedItems = requestDepartmentSearch(universityName)
            if (parsedItems.isEmpty()) return

            val cachedUniversities = upsertUniversities(
                parsedItems.mapNotNull { item -> item.toUniversityResponse() }
                    .distinctBy { normalizeKeyword(it.universityName) }
            )
            val universityByName = cachedUniversities.associateBy { normalizeKeyword(it.name) }

            parsedItems
                .mapNotNull { item ->
                    val department = item.toDepartmentResponse() ?: return@mapNotNull null
                    val responseUniversityName = item.valueOf("schlNm").ifBlank { universityName }
                    val university = universityByName[normalizeKeyword(responseUniversityName)] ?: return@mapNotNull null
                    university to department
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .forEach { (university, departments) ->
                    upsertDepartments(university, departments.distinctBy { normalizeKeyword(it.departmentName) })
                }
        } catch (ex: Exception) {
            logger.warn("학과 검색 캐시 워밍 실패 universityName={} reason={}", universityName, ex.rootMessage())
        } finally {
            inFlightWarmKeys.remove(warmKey)
        }
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULT_SIZE = 20
        private const val REMOTE_UNIVERSITY_PAGE_SIZE = 50
        private const val REMOTE_DEPARTMENT_PAGE_SIZE = 100
        private const val MAX_REMOTE_SEARCH_PAGES = 5
        private const val QUICK_REMOTE_UNIVERSITY_PAGES = 1
        private const val QUICK_REMOTE_DEPARTMENT_PAGES = 1
    }
}
