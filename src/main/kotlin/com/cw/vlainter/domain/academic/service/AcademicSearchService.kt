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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.util.HtmlUtils
import java.time.OffsetDateTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

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
    private val standardDataPortalClient: RestClient = restClientBuilder
        .baseUrl(STANDARD_DATA_PORTAL_BASE_URL)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(10_000)
                setReadTimeout(20_000)
            }
        )
        .build()
    private val objectMapper = jacksonObjectMapper()
    private val inFlightWarmKeys = ConcurrentHashMap.newKeySet<String>()
    private val standardDataCacheLock = Any()
    @Volatile
    private var standardDataCache: StandardDataCache? = null

    @Transactional
    fun searchUniversities(keyword: String): List<UniversitySearchItemResponse> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.length < MIN_QUERY_LENGTH) return emptyList()

        var source = "LOCAL"
        lateinit var results: List<UniversitySearchItemResponse>
        val elapsedMs = measureTimeMillis {
            val localResults = academicUniversityRepository.searchByKeyword(normalizeKeyword(normalizedKeyword))
                .map { it.toResponse() }
                .take(MAX_RESULT_SIZE)
            if (localResults.isNotEmpty()) {
                results = localResults
                return@measureTimeMillis
            }
            if (academyInfoServiceKey.isBlank()) {
                source = "STANDARD_DATA"
                results = searchUniversitiesFromStandardData(normalizedKeyword)
                    .let(::upsertUniversities)
                    .map { it.toResponse() }
                    .filter { it.universityName.contains(normalizedKeyword, ignoreCase = true) }
                    .take(MAX_RESULT_SIZE)
                return@measureTimeMillis
            }

            val quickRemoteCandidates = requestUniversitySearch(
                keyword = normalizedKeyword,
                maxPages = QUICK_REMOTE_UNIVERSITY_PAGES
            )
                .mapNotNull { item -> item.toUniversityResponse() }
                .distinctBy { normalizeKeyword(it.universityName) }
            val remoteCandidates = if (quickRemoteCandidates.isNotEmpty()) {
                source = "REMOTE_API"
                quickRemoteCandidates
            } else {
                source = "STANDARD_DATA_FALLBACK"
                searchUniversitiesFromStandardData(normalizedKeyword)
            }
            val remoteResults = upsertUniversities(remoteCandidates)
                .map { it.toResponse() }
                .filter { it.universityName.contains(normalizedKeyword, ignoreCase = true) }
                .take(MAX_RESULT_SIZE)
            triggerUniversityWarmAsync(normalizedKeyword)
            results = mergeUniversityResults(localResults, remoteResults)
        }
        logger.info(
            "academic university search timing keyword='{}' source={} resultCount={} elapsedMs={}",
            normalizedKeyword,
            source,
            results.size,
            elapsedMs
        )
        return results
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

        var source = "LOCAL"
        lateinit var results: List<DepartmentSearchItemResponse>
        val elapsedMs = measureTimeMillis {
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
            if (localResults.isNotEmpty()) {
                results = localResults
                return@measureTimeMillis
            }
            if (academyInfoServiceKey.isBlank()) {
                source = "STANDARD_DATA"
                val fallbackDepartments = searchDepartmentsFromStandardData(
                    universityName = normalizedUniversityName,
                    keyword = normalizedKeyword
                )
                if (fallbackDepartments.isEmpty()) {
                    results = localResults
                    return@measureTimeMillis
                }
                val fallbackUniversity = localUniversity ?: upsertUniversities(
                    listOf(UniversitySearchItemResponse(universityName = normalizedUniversityName))
                ).first()
                results = upsertDepartments(fallbackUniversity, fallbackDepartments)
                    .map { it.toResponse() }
                    .take(MAX_RESULT_SIZE)
                return@measureTimeMillis
            }

            val parsedItems = requestDepartmentSearch(
                universityName = normalizedUniversityName,
                maxPages = QUICK_REMOTE_DEPARTMENT_PAGES
            )
            if (parsedItems.isEmpty()) {
                source = "STANDARD_DATA_FALLBACK"
                val fallbackDepartments = searchDepartmentsFromStandardData(
                    universityName = normalizedUniversityName,
                    keyword = normalizedKeyword
                )
                if (fallbackDepartments.isEmpty()) {
                    results = localResults
                    return@measureTimeMillis
                }
                val fallbackUniversity = localUniversity ?: upsertUniversities(
                    listOf(UniversitySearchItemResponse(universityName = normalizedUniversityName))
                ).first()
                val remoteResults = upsertDepartments(fallbackUniversity, fallbackDepartments)
                    .map { it.toResponse() }
                triggerDepartmentWarmAsync(normalizedUniversityName)
                results = mergeDepartmentResults(localResults, remoteResults)
                return@measureTimeMillis
            }

            source = "REMOTE_API"
            val cachedUniversities = upsertUniversities(
                parsedItems.mapNotNull { item -> item.toUniversityResponse() }
                    .distinctBy { normalizeKeyword(it.universityName) }
            )
            val universityByName = cachedUniversities.associateBy { normalizeKeyword(it.name) }
            val targetUniversity = localUniversity
                ?: universityByName[normalizeKeyword(normalizedUniversityName)]
                ?: run {
                    results = localResults
                    return@measureTimeMillis
                }

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
            results = mergeDepartmentResults(localResults, remoteResults)
        }
        logger.info(
            "academic department search timing university='{}' keyword='{}' source={} resultCount={} elapsedMs={}",
            normalizedUniversityName,
            normalizedKeyword,
            source,
            results.size,
            elapsedMs
        )
        return results
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

    @Transactional
    fun importAllFromStandardData(): StandardDataImportSummary {
        val rows = loadStandardDataRows()
        if (rows.isEmpty()) {
            return StandardDataImportSummary()
        }

        return importAcademicRows(
            rows.map { row ->
                AcademicMetadataImportRow(
                    universityName = row.universityName,
                    departmentName = row.departmentName,
                    departmentCode = row.departmentCode
                )
            }
        )
    }

    @Transactional
    fun importAcademicRows(rows: List<AcademicMetadataImportRow>): StandardDataImportSummary {
        if (rows.isEmpty()) {
            return StandardDataImportSummary()
        }

        val rowsByUniversity = rows.groupBy { normalizeKeyword(it.universityName) }
        val normalizedUniversityNames = rowsByUniversity.keys
        val existingUniversitiesByName = academicUniversityRepository.findAllByNormalizedNameIn(normalizedUniversityNames)
            .associateBy { university -> university.normalizedName }
            .toMutableMap()
        var importedUniversities = 0
        var importedDepartments = 0
        val now = OffsetDateTime.now()
        val universitiesToSave = mutableListOf<AcademicUniversity>()

        rowsByUniversity.forEach { (normalizedUniversityName, universityRows) ->
            val representative = universityRows.first()
            val existingUniversity = existingUniversitiesByName[normalizedUniversityName]
            val university = existingUniversity ?: AcademicUniversity(
                name = representative.universityName,
                normalizedName = normalizedUniversityName,
                lastSyncedAt = now
            ).also { created ->
                existingUniversitiesByName[normalizedUniversityName] = created
                universitiesToSave += created
            }
            if (existingUniversity == null) {
                importedUniversities += 1
            } else if (university.lastSyncedAt != now || university.name != representative.universityName) {
                university.name = representative.universityName
                university.normalizedName = normalizedUniversityName
                university.lastSyncedAt = now
                universitiesToSave += university
            }
        }

        if (universitiesToSave.isNotEmpty()) {
            academicUniversityRepository.saveAllAndFlush(universitiesToSave.distinctBy { it.normalizedName })
        }

        val universitiesByName = academicUniversityRepository.findAllByNormalizedNameIn(normalizedUniversityNames)
            .associateBy { university -> university.normalizedName }
        val universityIds = universitiesByName.values.map { it.id }
        val existingDepartmentsByUniversityId = academicDepartmentRepository.findAllByUniversityIdIn(universityIds)
            .groupBy { department -> department.university.id }
        val departmentsToSave = mutableListOf<AcademicDepartment>()

        rowsByUniversity.forEach { (normalizedUniversityName, universityRows) ->
            val university = universitiesByName[normalizedUniversityName]
                ?: return@forEach
            val existingDepartments = existingDepartmentsByUniversityId[university.id].orEmpty()
            val existingByName = existingDepartments.associateBy { department -> department.normalizedName }.toMutableMap()
            val existingByCode = existingDepartments
                .mapNotNull { department -> department.externalCode?.let { code -> code to department } }
                .toMap()
                .toMutableMap()

            val uniqueDepartments = universityRows
                .map { row ->
                    DepartmentSearchItemResponse(
                        universityId = university.id,
                        universityName = university.name,
                        departmentName = row.departmentName,
                        departmentCode = row.departmentCode
                    )
                }
                .groupBy { item -> normalizeKeyword(item.departmentName) }
                .values
                .map { duplicates ->
                    duplicates.firstOrNull { !it.departmentCode.isNullOrBlank() } ?: duplicates.first()
                }

            uniqueDepartments.forEach { item ->
                val normalizedDepartmentName = normalizeKeyword(item.departmentName)
                val existing = existingByName[normalizedDepartmentName]
                    ?: item.departmentCode?.let { existingByCode[it] }
                val target = existing ?: AcademicDepartment(
                    university = university,
                    externalCode = item.departmentCode,
                    name = item.departmentName,
                    normalizedName = normalizedDepartmentName,
                    lastSyncedAt = now
                ).also {
                    importedDepartments += 1
                }

                target.university = university
                target.name = item.departmentName
                target.normalizedName = normalizedDepartmentName
                if (!item.departmentCode.isNullOrBlank()) {
                    target.externalCode = item.departmentCode
                }
                target.lastSyncedAt = now
                existingByName[normalizedDepartmentName] = target
                target.externalCode?.let { code -> existingByCode[code] = target }
                departmentsToSave += target
            }
        }

        if (departmentsToSave.isNotEmpty()) {
            academicDepartmentRepository.saveAll(departmentsToSave)
        }

        return StandardDataImportSummary(
            totalRows = rows.size,
            importedUniversities = importedUniversities,
            importedDepartments = importedDepartments,
            totalUniversities = rowsByUniversity.size
        )
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
            saveUniversitySafely(target, normalizedName, item.universityCode)
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
            val responseBody = fetchPublicUniversityMajorApiPage(
                path = path,
                pageNo = pageNo,
                pageSize = pageSize,
                extraParams = extraParams
            ) ?: break

            val pageItems = parseItems(responseBody)
            if (pageItems.isEmpty()) break
            collected += pageItems
            if (pageItems.size < pageSize) break
            pageNo += 1
        }
        return collected
    }

    private fun searchUniversitiesFromStandardData(keyword: String): List<UniversitySearchItemResponse> {
        val rows = loadStandardDataRows()
        if (rows.isEmpty()) return emptyList()
        logger.warn("대학교 공공데이터 API 실패로 포털 표준데이터 fallback 사용 keyword={}", keyword)
        return rows.asSequence()
            .filter { row -> row.universityName.contains(keyword, ignoreCase = true) }
            .map { row ->
                UniversitySearchItemResponse(
                    universityName = row.universityName
                )
            }
            .distinctBy { normalizeKeyword(it.universityName) }
            .take(MAX_RESULT_SIZE)
            .toList()
    }

    private fun searchDepartmentsFromStandardData(universityName: String, keyword: String): List<DepartmentSearchItemResponse> {
        val normalizedUniversityName = normalizeKeyword(universityName)
        val rows = loadStandardDataRows()
        if (rows.isEmpty()) return emptyList()
        logger.warn(
            "학과 공공데이터 API 실패로 포털 표준데이터 fallback 사용 universityName={} keyword={}",
            universityName,
            keyword
        )
        return rows.asSequence()
            .filter { row -> normalizeKeyword(row.universityName) == normalizedUniversityName }
            .filter { row -> row.departmentName.contains(keyword, ignoreCase = true) }
            .map { row ->
                DepartmentSearchItemResponse(
                    universityName = row.universityName,
                    departmentName = row.departmentName,
                    departmentCode = row.departmentCode
                )
            }
            .distinctBy { normalizeKeyword(it.departmentName) }
            .take(MAX_RESULT_SIZE)
            .toList()
    }

    private fun fetchPublicUniversityMajorApiPage(
        path: String,
        pageNo: Int,
        pageSize: Int,
        extraParams: (org.springframework.web.util.UriBuilder) -> org.springframework.web.util.UriBuilder
    ): String? {
        var attempt = 1
        while (attempt <= MAX_REMOTE_REQUEST_ATTEMPTS) {
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
                    "대학교/학과 공공데이터 API 호출 실패 path={} page={} attempt={}/{} reason={}",
                    path,
                    pageNo,
                    attempt,
                    MAX_REMOTE_REQUEST_ATTEMPTS,
                    ex.rootMessage()
                )
            }.getOrElse { null }

            if (responseBody != null) {
                return responseBody
            }

            if (attempt < MAX_REMOTE_REQUEST_ATTEMPTS) {
                try {
                    Thread.sleep(REMOTE_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            attempt += 1
        }
        return null
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

    private fun loadStandardDataRows(): List<StandardAcademicRow> {
        val now = Instant.now()
        val cached = standardDataCache
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.rows
        }
        synchronized(standardDataCacheLock) {
            val refreshedNow = Instant.now()
            val refreshedCache = standardDataCache
            if (refreshedCache != null && refreshedCache.expiresAt.isAfter(refreshedNow)) {
                return refreshedCache.rows
            }

            val html = runCatching {
                standardDataPortalClient.get()
                    .uri(STANDARD_DATASET_PATH)
                    .retrieve()
                    .body(String::class.java)
                    .orEmpty()
            }.onFailure { ex ->
                logger.warn("대학교/학과 포털 표준데이터 fallback 조회 실패 reason={}", ex.rootMessage())
            }.getOrElse { "" }
            if (html.isBlank()) return emptyList()

            val parsedRows = parseStandardDataRows(html)
            if (parsedRows.isNotEmpty()) {
                standardDataCache = StandardDataCache(
                    rows = parsedRows,
                    expiresAt = refreshedNow.plusSeconds(STANDARD_DATA_CACHE_TTL_SECONDS)
                )
            }
            return parsedRows
        }
    }

    private fun parseStandardDataRows(html: String): List<StandardAcademicRow> {
        return STANDARD_ROW_REGEX.findAll(html)
            .mapNotNull { match ->
                val cells = STANDARD_CELL_REGEX.findAll(match.groupValues[1])
                    .map { cellMatch -> stripHtml(cellMatch.groupValues[1]) }
                    .toList()
                if (cells.size <= STANDARD_DEPARTMENT_CODE_INDEX) return@mapNotNull null
                val universityName = cells[STANDARD_UNIVERSITY_NAME_INDEX]
                val departmentName = cells[STANDARD_DEPARTMENT_NAME_INDEX]
                if (universityName.isBlank() || departmentName.isBlank()) return@mapNotNull null
                StandardAcademicRow(
                    universityName = universityName,
                    departmentName = departmentName,
                    departmentCode = cells[STANDARD_DEPARTMENT_CODE_INDEX].takeIf { it.isNotBlank() }
                )
            }
            .toList()
    }

    private fun stripHtml(raw: String): String {
        return HtmlUtils.htmlUnescape(raw)
            .replace(HTML_TAG_REGEX, " ")
            .replace(HTML_WHITESPACE_REGEX, " ")
            .trim()
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

    private fun saveUniversitySafely(
        target: AcademicUniversity,
        normalizedName: String,
        externalCode: String?
    ): AcademicUniversity {
        return try {
            academicUniversityRepository.saveAndFlush(target)
        } catch (ex: DataIntegrityViolationException) {
            academicUniversityRepository.findByNormalizedName(normalizedName)
                ?: externalCode?.let { academicUniversityRepository.findByExternalCode(it) }
                ?: throw ex
        }
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

    private data class StandardAcademicRow(
        val universityName: String,
        val departmentName: String,
        val departmentCode: String?
    )

    private data class StandardDataCache(
        val rows: List<StandardAcademicRow>,
        val expiresAt: Instant
    )

    data class StandardDataImportSummary(
        val totalRows: Int = 0,
        val importedUniversities: Int = 0,
        val importedDepartments: Int = 0,
        val totalUniversities: Int = 0
    )

    data class AcademicMetadataImportRow(
        val universityName: String,
        val departmentName: String,
        val departmentCode: String? = null
    )

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
        private const val MAX_REMOTE_REQUEST_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 250L
        private const val STANDARD_DATA_PORTAL_BASE_URL = "https://www.data.go.kr"
        private const val STANDARD_DATASET_PATH = "/data/15107737/standard.do"
        private const val STANDARD_DATA_CACHE_TTL_SECONDS = 21_600L
        private const val STANDARD_UNIVERSITY_NAME_INDEX = 5
        private const val STANDARD_DEPARTMENT_NAME_INDEX = 11
        private const val STANDARD_DEPARTMENT_CODE_INDEX = 12
        private val STANDARD_ROW_REGEX = Regex("""<tr class="contentsTr">(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val STANDARD_CELL_REGEX = Regex("""<td>\s*(.*?)\s*</td>""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val HTML_WHITESPACE_REGEX = Regex("""\s+""")
    }
}
