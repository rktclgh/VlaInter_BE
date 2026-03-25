package com.cw.vlainter.domain.interview.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

object HistoryPageRequestValidator {
    const val DEFAULT_MAX_PAGE_SIZE = 24

    fun validate(page: Int, size: Int, maxPageSize: Int = DEFAULT_MAX_PAGE_SIZE) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size !in 1..maxPageSize) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상 ${maxPageSize} 이하여야 합니다.")
        }
    }
}
