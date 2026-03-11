package com.cw.vlainter.domain.auth.repository

import com.cw.vlainter.domain.auth.entity.UserAccessSessionLog
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface UserAccessSessionLogRepository : JpaRepository<UserAccessSessionLog, Long> {
    fun findBySessionId(sessionId: String): UserAccessSessionLog?

    fun findByUser_IdOrderByLoginAtDesc(userId: Long, pageable: Pageable): List<UserAccessSessionLog>

    fun findByUser_IdAndLoginAtGreaterThanEqualOrderByLoginAtDesc(userId: Long, threshold: OffsetDateTime): List<UserAccessSessionLog>

    fun findByLoginAtGreaterThanEqualOrderByLoginAtDesc(threshold: OffsetDateTime): List<UserAccessSessionLog>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update UserAccessSessionLog l
        set l.logoutAt = :now,
            l.lastActivityAt = :now,
            l.active = false
        where l.sessionId = :sessionId
        """
    )
    fun markLogout(@Param("sessionId") sessionId: String, @Param("now") now: OffsetDateTime): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update UserAccessSessionLog l
        set l.lastActivityAt = :now,
            l.actionCount = l.actionCount + 1
        where l.sessionId = :sessionId
          and l.active = true
        """
    )
    fun incrementAction(
        @Param("sessionId") sessionId: String,
        @Param("now") now: OffsetDateTime
    ): Int
}
