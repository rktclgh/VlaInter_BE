package com.cw.vlainter.global.exception

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    private val missingFieldRegex = Regex("missing (?:creator )?property '([^']+)'", RegexOption.IGNORE_CASE)

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        val resolvedStatus = HttpStatus.resolve(ex.statusCode.value())
        val statusValue = ex.statusCode.value()
        val statusCodeName = resolvedStatus?.name ?: "HTTP_$statusValue"
        return ResponseEntity
            .status(ex.statusCode)
            .body(
                ApiErrorResponse(
                    status = statusValue,
                    code = statusCodeName,
                    message = ex.reason?.takeIf { it.isNotBlank() } ?: defaultMessage(resolvedStatus),
                    path = request.requestURI
                )
            )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.map { fieldError(it) }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "VALIDATION_ERROR",
                message = "요청 값이 올바르지 않습니다.",
                path = request.requestURI,
                errors = errors
            )
        )
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "MISSING_PARAMETER",
                message = "필수 요청 파라미터가 누락되었습니다: ${ex.parameterName}",
                path = request.requestURI
            )
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        val missingField = findMissingField(ex)
        val message = if (missingField == null) {
            "요청 본문 형식이 올바르지 않습니다."
        } else {
            "요청 본문에 필수 값이 누락되었습니다: $missingField"
        }

        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_REQUEST_BODY",
                message = message,
                path = request.requestURI
            )
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        val errors = ex.constraintViolations.map {
            ApiFieldError(
                field = it.propertyPath.toString(),
                message = it.message
            )
        }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "CONSTRAINT_VIOLATION",
                message = "요청 값 제약 조건을 만족하지 않습니다.",
                path = request.requestURI,
                errors = errors
            )
        )
    }


    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(
        ex: MaxUploadSizeExceededException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ApiErrorResponse(
                status = HttpStatus.PAYLOAD_TOO_LARGE.value(),
                code = "PAYLOAD_TOO_LARGE",
                message = "업로드 가능한 파일 크기를 초과했습니다.",
                path = request.requestURI
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnhandledException(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled exception while processing request path={}", request.requestURI, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                code = "INTERNAL_SERVER_ERROR",
                message = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                path = request.requestURI
            )
        )
    }

    private fun fieldError(fieldError: FieldError): ApiFieldError {
        return ApiFieldError(
            field = fieldError.field,
            message = fieldError.defaultMessage ?: "값이 올바르지 않습니다."
        )
    }

    private fun findMissingField(ex: HttpMessageNotReadableException): String? {
        var cause: Throwable? = ex
        while (cause != null) {
            val message = cause.message.orEmpty()
            val match = missingFieldRegex.find(message)
            if (match != null) return match.groupValues[1]
            cause = cause.cause
        }
        return null
    }

    private fun defaultMessage(status: HttpStatus?): String {
        return when (status) {
            HttpStatus.BAD_REQUEST -> "잘못된 요청입니다."
            HttpStatus.UNAUTHORIZED -> "인증이 필요합니다. 다시 로그인해 주세요."
            HttpStatus.FORBIDDEN -> "접근 권한이 없습니다."
            HttpStatus.NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다."
            HttpStatus.TOO_MANY_REQUESTS -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
            HttpStatus.SERVICE_UNAVAILABLE -> "현재 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
            else -> "요청 처리 중 오류가 발생했습니다."
        }
    }
}
