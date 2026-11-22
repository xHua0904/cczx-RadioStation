package com.cczxcsc.controller

import com.cczxcsc.common.ApiError
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.*
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.util.DisconnectedClientHelper

@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(ApiErrors::class.java)
    private val disconnected = DisconnectedClientHelper("com.cczxcsc.ClientDisconnect")
    @ExceptionHandler(ApiError::class)
    fun api(error: ApiError) = ResponseEntity.status(error.status).body(mapOf("error" to error.message))
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed() = ResponseEntity.badRequest().body(mapOf("error" to "请求格式有误"))
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, response: HttpServletResponse): ResponseEntity<Map<String, String>>? {
        // 客户端中断连接后不能再次写入 JSON，尤其是已开始发送的图片和导出文件。
        if (disconnected.checkAndLogClientDisconnectedException(error)) return null
        if (response.isCommitted) { log.error("响应发送后处理失败", error); return null }
        response.resetBuffer()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        if (error is ErrorResponse) return ResponseEntity.status(error.statusCode).body(mapOf("error" to when (error.statusCode.value()) {
            404 -> "接口或页面不存在"
            405 -> "不支持的请求方法"
            else -> "请求格式有误"
        }))
        log.error("接口处理失败", error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "服务器内部错误"))
    }
}
