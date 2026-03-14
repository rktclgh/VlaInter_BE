package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.AdminInterviewSetting
import org.springframework.data.jpa.repository.JpaRepository

interface AdminInterviewSettingRepository : JpaRepository<AdminInterviewSetting, String>
