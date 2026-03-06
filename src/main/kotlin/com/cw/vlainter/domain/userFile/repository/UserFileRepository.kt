package com.cw.vlainter.domain.userFile.repository

import com.cw.vlainter.domain.userFile.entity.UserFile
import com.cw.vlainter.domain.userFile.entity.FileType
import org.springframework.data.jpa.repository.JpaRepository

interface UserFileRepository : JpaRepository<UserFile, Long> {
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long): List<UserFile>
    fun findByUser_IdAndFileType(userId: Long, fileType: FileType): UserFile?
    fun findTopByUser_IdAndFileTypeOrderByCreatedAtDesc(userId: Long, fileType: FileType): UserFile?
    fun deleteAllByUser_Id(userId: Long)
}
