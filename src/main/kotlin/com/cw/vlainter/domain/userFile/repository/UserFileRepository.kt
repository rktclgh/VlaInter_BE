package com.cw.vlainter.domain.userFile.repository

import com.cw.vlainter.domain.userFile.entity.UserFile
import org.springframework.data.jpa.repository.JpaRepository

interface UserFileRepository : JpaRepository<UserFile, Long> {
    fun deleteAllByUser_Id(userId: Long)
}
