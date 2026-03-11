package com.cw.vlainter.domain.user.repository

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    fun existsByEmailAndIdNot(email: String, id: Long): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    fun findByIdForUpdate(@Param("userId") userId: Long): User?

    /**
     * 결제 확정 등으로 포인트를 적립한다. delta는 0보다 커야 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.point = u.point + :delta where u.id = :userId")
    fun rewardPoint(@Param("userId") userId: Long, @Param("delta") delta: Long): Int

    /**
     * 포인트 차감 시 잔액이 음수가 되지 않는 경우에만 반영한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.point = u.point + :delta where u.id = :userId and (u.point + :delta) >= 0")
    fun addPointIfNotNegative(@Param("userId") userId: Long, @Param("delta") delta: Long): Int

    @Query(
        """
        select u
        from User u
        where u.role = :role
          and u.status <> :excludedStatus
          and trim(u.email) <> ''
        order by u.id asc
        """
    )
    fun findReportRecipients(
        @Param("role") role: UserRole = UserRole.ADMIN,
        @Param("excludedStatus") excludedStatus: UserStatus = UserStatus.DELETED
    ): List<User>
    fun countByStatusNot(status: UserStatus): Long

    @Query(
        """
        select u
        from User u
        where (:keyword = '' or lower(u.email) like lower(concat('%', :keyword, '%'))
           or lower(u.name) like lower(concat('%', :keyword, '%'))
           or str(u.id) = :keyword)
        """
    )
    fun searchMembers(@Param("keyword") keyword: String, pageable: Pageable): Page<User>
}
