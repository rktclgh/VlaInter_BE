package com.cw.vlainter.domain.userFile.repository

import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.entity.FileType
import org.springframework.data.jpa.repository.JpaRepository

interface UserFileRepository : JpaRepository<UserFile, Long> {
    fun findAllByUser_IdAndDeletedAtIsNullOrderByCreatedAtDesc(userId: Long): List<UserFile>
    fun findAllByUser_IdAndFileTypeAndDeletedAtIsNullOrderByCreatedAtDesc(userId: Long, fileType: FileType): List<UserFile>
    fun findTopByUser_IdAndFileTypeAndIsActiveTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
        userId: Long,
        fileType: FileType
    ): UserFile?
    fun countByUser_IdAndFileTypeAndDeletedAtIsNull(userId: Long, fileType: FileType): Long
    fun findByIdAndUser_IdAndDeletedAtIsNull(id: Long, userId: Long): UserFile?
    fun deleteAllByUser_Id(userId: Long)
}
