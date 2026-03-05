package com.cw.vlainter.domain.payment.repository

import com.cw.vlainter.domain.payment.entity.PointCharge
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import org.springframework.data.repository.query.Param
import java.util.Optional

interface PointChargeRepository : JpaRepository<PointCharge, Long> {
    fun findByMerchantUid(merchantUid: String): Optional<PointCharge>
    fun findByImpUid(impUid: String): Optional<PointCharge>
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<PointCharge>
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long): List<PointCharge>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pc from PointCharge pc where pc.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): PointCharge?
}
