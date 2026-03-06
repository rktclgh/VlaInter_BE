package com.cw.vlainter.domain.payment.repository

import com.cw.vlainter.domain.payment.model.PointChargePolicy
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PointChargePolicyRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {
    private val rowMapper = RowMapper { rs, _ ->
        PointChargePolicy(
            productId = rs.getString("product_id"),
            amount = rs.getInt("amount"),
            rewardPoint = rs.getLong("reward_point"),
            sortOrder = rs.getInt("sort_order")
        )
    }

    fun findAllActive(): List<PointChargePolicy> {
        val sql = """
            SELECT product::text AS product_id, amount, reward_point, sort_order
            FROM product
            WHERE is_active = TRUE
              AND amount IS NOT NULL
              AND reward_point IS NOT NULL
            ORDER BY sort_order ASC, id ASC
        """.trimIndent()

        return jdbcTemplate.query(sql, rowMapper)
    }

    fun findActiveByProductId(productId: String): PointChargePolicy? {
        val sql = """
            SELECT product::text AS product_id, amount, reward_point, sort_order
            FROM product
            WHERE is_active = TRUE
              AND amount IS NOT NULL
              AND reward_point IS NOT NULL
              AND product::text = :productId
            ORDER BY sort_order ASC, id ASC
            LIMIT 1
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("productId", productId)

        return jdbcTemplate.query(sql, params, rowMapper).firstOrNull()
    }
}
