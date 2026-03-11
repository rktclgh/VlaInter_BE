package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.auth.entity.UserAccessGlobalSummarySnapshot
import com.cw.vlainter.domain.auth.entity.UserAccessSessionLog
import com.cw.vlainter.domain.auth.entity.UserAccessSummarySnapshot
import com.cw.vlainter.domain.auth.repository.UserAccessGlobalSummarySnapshotRepository
import com.cw.vlainter.domain.auth.repository.UserAccessSessionLogRepository
import com.cw.vlainter.domain.auth.repository.UserAccessSummarySnapshotRepository
import com.cw.vlainter.domain.interview.entity.InterviewStatus
import com.cw.vlainter.domain.interview.repository.InterviewSessionRepository
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

data class AuthAccessAuditEntry(
    val sessionId: String,
    val userId: Long,
    val email: String,
    val authProvider: AuthProviderType,
    val loginAt: OffsetDateTime,
    val lastActivityAt: OffsetDateTime?,
    val logoutAt: OffsetDateTime?,
    val actionCount: Long,
    val ipAddress: String?,
    val userAgent: String?,
    val active: Boolean
)

data class AuthAccessDailyCount(
    val date: String,
    val loginCount: Int
)

data class AuthAccessAuditSummary(
    val recentLoginCount: Int,
    val activeSessionCount: Int,
    val totalActionCount: Long,
    val averageActionCount: Double,
    val averageSessionMinutes: Long,
    val lastLoginAt: OffsetDateTime?,
    val completedInterviewCount: Long,
    val totalInterviewCount: Long,
    val interviewCompletionRate: Double,
    val dailyLoginCounts: List<AuthAccessDailyCount>,
    val calculatedAt: OffsetDateTime?
)

data class AuthAccessGlobalDailyMetric(
    val date: String,
    val averageLoginCount: Double,
    val averageActionCount: Double,
    val averageSessionMinutes: Double
)

data class AuthAccessGlobalSummary(
    val windowDays: Int,
    val totalMemberCount: Long,
    val totalLoginCount: Long,
    val totalActionCount: Long,
    val averageLoginCount: Double,
    val averageActionCount: Double,
    val averageSessionMinutes: Double,
    val averageActiveSessionCount: Double,
    val calculatedAt: OffsetDateTime?,
    val dailyMetrics: List<AuthAccessGlobalDailyMetric>
)

@Service
class AuthAccessAuditService(
    private val userRepository: UserRepository,
    private val sessionLogRepository: UserAccessSessionLogRepository,
    private val summarySnapshotRepository: UserAccessSummarySnapshotRepository,
    private val globalSummarySnapshotRepository: UserAccessGlobalSummarySnapshotRepository,
    private val interviewSessionRepository: InterviewSessionRepository,
    transactionManager: PlatformTransactionManager
) {
    private val objectMapper = jacksonObjectMapper()
    private val writeTransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = false
    }

    @Transactional
    fun recordLogin(
        sessionId: String,
        userId: Long,
        email: String,
        authProvider: AuthProviderType,
        ipAddress: String?,
        userAgent: String?
    ) {
        val now = OffsetDateTime.now()
        val userRef = userRepository.getReferenceById(userId)
        val existing = sessionLogRepository.findBySessionId(sessionId)
        val target = existing ?: UserAccessSessionLog(
            user = userRef,
            sessionId = sessionId,
            emailSnapshot = email,
            authProvider = authProvider,
            loginAt = now
        )
        target.emailSnapshot = email
        target.authProvider = authProvider
        target.loginAt = now
        target.lastActivityAt = now
        target.logoutAt = null
        target.actionCount = 0
        target.ipAddress = ipAddress
        target.userAgent = userAgent
        target.lastMethod = null
        target.lastPath = null
        target.active = true
        sessionLogRepository.save(target)
    }

    @Transactional
    fun touchActivity(sessionId: String, method: String) {
        if (method.uppercase() !in MUTATING_METHODS) return
        sessionLogRepository.incrementAction(sessionId, OffsetDateTime.now())
    }

    @Transactional
    fun markLogout(sessionId: String) {
        sessionLogRepository.markLogout(sessionId, OffsetDateTime.now())
    }

    @Transactional(readOnly = true)
    fun getRecentEntriesForUser(userId: Long, limit: Int = 10): List<AuthAccessAuditEntry> {
        return sessionLogRepository.findByUser_IdOrderByLoginAtDesc(userId, PageRequest.of(0, limit))
            .map { it.toEntry() }
    }

    @Transactional(readOnly = true)
    fun getLastLoginForUser(userId: Long): AuthAccessAuditEntry? {
        return sessionLogRepository.findByUser_IdOrderByLoginAtDesc(userId, PageRequest.of(0, 1))
            .firstOrNull()
            ?.toEntry()
    }

    @Transactional
    fun getSummaryForUser(userId: Long, days: Int = 7, forceRefresh: Boolean = false): AuthAccessAuditSummary {
        if (forceRefresh) {
            return recomputeSummaryForUser(userId, days)
        }
        val snapshot = summarySnapshotRepository.findByUser_IdAndWindowDays(userId, days)
        return snapshot?.toSummary() ?: recomputeSummaryForUser(userId, days)
    }

    fun recomputeSummaryForUser(userId: Long, days: Int = 7): AuthAccessAuditSummary {
        return writeTransactionTemplate.execute<AuthAccessAuditSummary> {
            recomputeSummaryForUserInWriteTransaction(userId, days)
        } ?: throw IllegalStateException("사용자 접속 요약을 재계산하지 못했습니다.")
    }

    private fun recomputeSummaryForUserInWriteTransaction(userId: Long, days: Int): AuthAccessAuditSummary {
        val threshold = OffsetDateTime.now().minusDays(days.toLong())
        val entries = sessionLogRepository.findByUser_IdAndLoginAtGreaterThanEqualOrderByLoginAtDesc(userId, threshold)
        val dailyLoginCounts = buildDailyCounts(entries, days)
        val recentLoginCount = entries.size
        val activeSessionCount = entries.count { it.active }
        val totalActionCount = entries.sumOf { it.actionCount }
        val averageActionCount = if (entries.isEmpty()) 0.0 else totalActionCount.toDouble() / entries.size.toDouble()
        val averageSessionMinutes = entries.mapNotNull { entry ->
            val end = entry.logoutAt ?: entry.lastActivityAt
            end?.let { Duration.between(entry.loginAt, it).toMinutes().coerceAtLeast(0) }
        }.let { durations ->
            if (durations.isEmpty()) 0L else durations.average().roundToLong()
        }
        val lastLoginAt = entries.maxByOrNull { it.loginAt }?.loginAt
        val totalInterviewCount = interviewSessionRepository.countByUser_Id(userId)
        val completedInterviewCount = interviewSessionRepository.countByUser_IdAndStatus(userId, InterviewStatus.DONE)
        val completionRate = if (totalInterviewCount == 0L) 0.0 else completedInterviewCount.toDouble() / totalInterviewCount.toDouble()

        val userRef = userRepository.getReferenceById(userId)
        val snapshot = summarySnapshotRepository.findByUser_IdAndWindowDays(userId, days)
            ?: UserAccessSummarySnapshot(user = userRef, windowDays = days)
        snapshot.recentLoginCount = recentLoginCount
        snapshot.activeSessionCount = activeSessionCount
        snapshot.totalActionCount = totalActionCount
        snapshot.averageActionCount = averageActionCount
        snapshot.averageSessionMinutes = averageSessionMinutes
        snapshot.lastLoginAt = lastLoginAt
        snapshot.completedInterviewCount = completedInterviewCount
        snapshot.totalInterviewCount = totalInterviewCount
        snapshot.interviewCompletionRate = completionRate
        snapshot.dailyLoginCountsJson = objectMapper.writeValueAsString(dailyLoginCounts)
        snapshot.calculatedAt = OffsetDateTime.now()
        val saved = summarySnapshotRepository.save(snapshot)
        return saved.toSummary()
    }

    @Transactional
    fun getGlobalSummary(days: Int = 7, forceRefresh: Boolean = false): AuthAccessGlobalSummary {
        if (forceRefresh) {
            return recomputeGlobalSummary(days)
        }
        val snapshot = globalSummarySnapshotRepository.findByWindowDays(days)
        return snapshot?.toGlobalSummary() ?: recomputeGlobalSummary(days)
    }

    fun recomputeGlobalSummary(days: Int = 7): AuthAccessGlobalSummary {
        return writeTransactionTemplate.execute<AuthAccessGlobalSummary> {
            recomputeGlobalSummaryInWriteTransaction(days)
        } ?: throw IllegalStateException("전체 접속 요약을 재계산하지 못했습니다.")
    }

    private fun recomputeGlobalSummaryInWriteTransaction(days: Int): AuthAccessGlobalSummary {
        val threshold = OffsetDateTime.now().minusDays(days.toLong())
        val entries = sessionLogRepository.findByLoginAtGreaterThanEqualOrderByLoginAtDesc(threshold)
        val totalMemberCount = userRepository.countByStatusNot(UserStatus.DELETED)
        val denominator = totalMemberCount.coerceAtLeast(1)
        val totalLoginCount = entries.size.toLong()
        val totalActionCount = entries.sumOf { it.actionCount }
        val averageLoginCount = totalLoginCount.toDouble() / denominator.toDouble()
        val averageActionCount = totalActionCount.toDouble() / denominator.toDouble()
        val averageSessionMinutes = entries.mapNotNull { entry ->
            val end = entry.logoutAt ?: entry.lastActivityAt
            end?.let { Duration.between(entry.loginAt, it).toMinutes().coerceAtLeast(0) }
        }.let { durations ->
            if (durations.isEmpty()) 0.0 else durations.average()
        }
        val activeSessionCount = entries.count { it.active }
        val averageActiveSessionCount = activeSessionCount.toDouble() / denominator.toDouble()
        val dailyMetrics = buildGlobalDailyMetrics(entries, days, denominator)

        val snapshot = globalSummarySnapshotRepository.findByWindowDays(days)
            ?: UserAccessGlobalSummarySnapshot(windowDays = days)
        snapshot.totalMemberCount = totalMemberCount
        snapshot.totalLoginCount = totalLoginCount
        snapshot.totalActionCount = totalActionCount
        snapshot.averageLoginCount = averageLoginCount
        snapshot.averageActionCount = averageActionCount
        snapshot.averageSessionMinutes = averageSessionMinutes
        snapshot.averageActiveSessionCount = averageActiveSessionCount
        snapshot.dailyMetricsJson = objectMapper.writeValueAsString(dailyMetrics)
        snapshot.calculatedAt = OffsetDateTime.now()
        val saved = globalSummarySnapshotRepository.save(snapshot)
        return saved.toGlobalSummary()
    }

    @Scheduled(cron = "0 0 */12 * * *")
    @Transactional
    fun recomputeGlobalSummariesEvery12Hours() {
        listOf(7, 30).forEach { days ->
            recomputeGlobalSummary(days)
        }
    }

    private fun buildDailyCounts(entries: List<UserAccessSessionLog>, days: Int): List<AuthAccessDailyCount> {
        val counts = entries.groupingBy { it.loginAt.toLocalDate() }.eachCount()
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            AuthAccessDailyCount(
                date = day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                loginCount = counts[day] ?: 0
            )
        }
    }

    private fun buildGlobalDailyMetrics(
        entries: List<UserAccessSessionLog>,
        days: Int,
        totalMemberCount: Long
    ): List<AuthAccessGlobalDailyMetric> {
        val loginCountsByDate = entries.groupingBy { it.loginAt.toLocalDate() }.eachCount()
        val actionCountsByDate = entries.groupBy { it.loginAt.toLocalDate() }
            .mapValues { (_, logs) -> logs.sumOf { it.actionCount } }
        val avgSessionMinutesByDate = entries.groupBy { it.loginAt.toLocalDate() }
            .mapValues { (_, logs) ->
                val durations = logs.mapNotNull { entry ->
                    val end = entry.logoutAt ?: entry.lastActivityAt
                    end?.let { Duration.between(entry.loginAt, it).toMinutes().coerceAtLeast(0) }
                }
                if (durations.isEmpty()) 0.0 else durations.average()
            }
        val today = LocalDate.now()
        val denominator = totalMemberCount.coerceAtLeast(1).toDouble()
        return (days - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            AuthAccessGlobalDailyMetric(
                date = day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                averageLoginCount = (loginCountsByDate[day] ?: 0).toDouble() / denominator,
                averageActionCount = (actionCountsByDate[day] ?: 0L).toDouble() / denominator,
                averageSessionMinutes = avgSessionMinutesByDate[day] ?: 0.0
            )
        }
    }

    private fun UserAccessSessionLog.toEntry(): AuthAccessAuditEntry {
        return AuthAccessAuditEntry(
            sessionId = sessionId,
            userId = user.id,
            email = emailSnapshot,
            authProvider = authProvider,
            loginAt = loginAt,
            lastActivityAt = lastActivityAt,
            logoutAt = logoutAt,
            actionCount = actionCount,
            ipAddress = ipAddress,
            userAgent = userAgent,
            active = active
        )
    }

    private fun UserAccessSummarySnapshot.toSummary(): AuthAccessAuditSummary {
        val dailyCounts: List<AuthAccessDailyCount> = runCatching<List<AuthAccessDailyCount>> {
            objectMapper.readValue(
                dailyLoginCountsJson,
                objectMapper.typeFactory.constructCollectionType(List::class.java, AuthAccessDailyCount::class.java)
            )
        }.getOrDefault(emptyList())
        return AuthAccessAuditSummary(
            recentLoginCount = recentLoginCount,
            activeSessionCount = activeSessionCount,
            totalActionCount = totalActionCount,
            averageActionCount = averageActionCount,
            averageSessionMinutes = averageSessionMinutes,
            lastLoginAt = lastLoginAt,
            completedInterviewCount = completedInterviewCount,
            totalInterviewCount = totalInterviewCount,
            interviewCompletionRate = interviewCompletionRate,
            dailyLoginCounts = dailyCounts,
            calculatedAt = calculatedAt
        )
    }

    private fun UserAccessGlobalSummarySnapshot.toGlobalSummary(): AuthAccessGlobalSummary {
        val dailyMetrics: List<AuthAccessGlobalDailyMetric> = runCatching<List<AuthAccessGlobalDailyMetric>> {
            objectMapper.readValue(
                dailyMetricsJson,
                objectMapper.typeFactory.constructCollectionType(List::class.java, AuthAccessGlobalDailyMetric::class.java)
            )
        }.getOrDefault(emptyList())
        return AuthAccessGlobalSummary(
            windowDays = windowDays,
            totalMemberCount = totalMemberCount,
            totalLoginCount = totalLoginCount,
            totalActionCount = totalActionCount,
            averageLoginCount = averageLoginCount,
            averageActionCount = averageActionCount,
            averageSessionMinutes = averageSessionMinutes,
            averageActiveSessionCount = averageActiveSessionCount,
            calculatedAt = calculatedAt,
            dailyMetrics = dailyMetrics
        )
    }

    private companion object {
        val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
