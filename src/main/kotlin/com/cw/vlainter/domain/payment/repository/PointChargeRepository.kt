package com.cw.vlainter.domain.payment.repository

import com.cw.vlainter.domain.payment.entity.PointCharge
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface PointChargeRepository : JpaRepository<PointCharge, Long> {
    fun findByMerchantUid(merchantUid: String): Optional<PointCharge>
    fun findByImpUid(impUid: String): Optional<PointCharge>
}
