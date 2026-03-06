package com.cw.vlainter.domain.payment.repository

import com.cw.vlainter.domain.payment.entity.PointCharge
import com.cw.vlainter.domain.payment.entity.PointChargeStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.util.Optional

interface PointChargeRepository : JpaRepository<PointCharge, Long> {
    fun findByMerchantUid(merchantUid: String): Optional<PointCharge>
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<PointCharge>
    fun findAllByUser_IdAndStatusInOrderByPaidAtDescIdDesc(
        userId: Long,
        statuses: Collection<PointChargeStatus>,
        pageable: Pageable
    ): Page<PointCharge>

    fun findAllByUser_IdAndStatusOrderByUpdatedAtDescIdDesc(
        userId: Long,
        status: PointChargeStatus,
        pageable: Pageable
    ): Page<PointCharge>

    fun countByUser_IdAndStatusIn(userId: Long, statuses: Collection<PointChargeStatus>): Long
    fun countByUser_IdAndStatus(userId: Long, status: PointChargeStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): PointCharge?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByMerchantUid(merchantUid: String): PointCharge?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByImpUid(impUid: String): PointCharge?
}
