package com.cw.vlainter.domain.payment.repository

import com.cw.vlainter.domain.payment.entity.PointCharge
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.Optional

interface PointChargeRepository : JpaRepository<PointCharge, Long> {
    fun findByMerchantUid(merchantUid: String): Optional<PointCharge>
    fun findByImpUid(impUid: String): Optional<PointCharge>
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<PointCharge>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pc from PointCharge pc where pc.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): PointCharge?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pc from PointCharge pc where pc.merchantUid = :merchantUid")
    fun findByMerchantUidForUpdate(@Param("merchantUid") merchantUid: String): PointCharge?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pc from PointCharge pc where pc.impUid = :impUid")
    fun findByImpUidForUpdate(@Param("impUid") impUid: String): PointCharge?

    @Query(
        value = """
            SELECT ledger.occurred_at AS occurredAt, ledger.point_delta AS pointDelta, ledger.description AS description
            FROM (
                SELECT COALESCE(pc.paid_at, pc.created_at, pc.updated_at) AS occurred_at,
                       pc.reward_point AS point_delta,
                       '포인트 충전' AS description
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'PAID'
                UNION ALL
                SELECT COALESCE(pc.paid_at, pc.created_at, pc.updated_at) AS occurred_at,
                       pc.reward_point AS point_delta,
                       '포인트 충전' AS description
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'CANCELLED'
                UNION ALL
                SELECT COALESCE(pc.updated_at, pc.created_at) AS occurred_at,
                       -pc.reward_point AS point_delta,
                       '포인트 환불' AS description
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'CANCELLED'
            ) ledger
            ORDER BY ledger.occurred_at DESC
            LIMIT :limit OFFSET :offset
        """,
        nativeQuery = true
    )
    fun findLedgerRows(
        @Param("userId") userId: Long,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int
    ): List<PointLedgerRowProjection>

    @Query(
        value = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'PAID'
                UNION ALL
                SELECT 1
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'CANCELLED'
                UNION ALL
                SELECT 1
                FROM point_charges pc
                WHERE pc.user_id = :userId
                  AND pc.status = 'CANCELLED'
            ) ledger_count
        """,
        nativeQuery = true
    )
    fun countLedgerRows(@Param("userId") userId: Long): Long
}

interface PointLedgerRowProjection {
    fun getOccurredAt(): OffsetDateTime
    fun getPointDelta(): Long
    fun getDescription(): String
}
