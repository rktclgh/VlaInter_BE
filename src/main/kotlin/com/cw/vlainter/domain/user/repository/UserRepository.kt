package com.cw.vlainter.domain.user.repository

import com.cw.vlainter.domain.user.entity.User
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

/**
 * 사용자 인증/조회에 사용하는 User 저장소.
 */
interface UserRepository : JpaRepository<User, Long> {
    /**
     * 이메일(로그인 ID) 기준 사용자 조회.
     */
    fun findByEmail(email: String): Optional<User>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.point = u.point + :delta where u.id = :userId")
    fun addPoint(@Param("userId") userId: Long, @Param("delta") delta: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.point = u.point + :delta where u.id = :userId and (u.point + :delta) >= 0")
    fun addPointIfNotNegative(@Param("userId") userId: Long, @Param("delta") delta: Long): Int
}
