package com.cczxcsc.config

import com.cczxcsc.security.SubmissionPolicy
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RequestLimits(private val json: ObjectMapper, private val submissions: SubmissionPolicy) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        val path = request.servletPath.ifBlank { request.requestURI.removePrefix(request.contextPath) }
            .split('/').joinToString("/") { it.substringBefore(';') }
        // 页面每次重新获取，构建时的资源版本参数负责让脚本与样式同步更新。
        if (request.requestURI.startsWith("/api/") || path in setOf("", "/", "/admin") || path.endsWith(".html")) response.setHeader("Cache-Control", "no-store")
        val posting = request.method == "POST" && path == "/api/submissions"
        val publicRead = request.method == "POST" && path == "/api/submissions/progress" ||
            request.method == "GET" && path in setOf("/api/submissions/song-exists", "/api/schedule")
        if (posting || publicRead) {
            val retry = if (posting) submissions.retryAfter(request) else submissions.readRetryAfter(request)
            if (retry > 0) {
                response.status = 429
                response.setHeader("Retry-After", retry.toString())
                response.contentType = "application/json;charset=UTF-8"
                json.writeValue(response.outputStream, mapOf("error" to "${if (posting) "投稿" else "查询"}过于频繁，请在 $retry 秒后再试", "retry_after" to retry))
                return
            }
        }
        if (request.method !in setOf("POST", "PATCH", "PUT")) { chain.doFilter(request, response); return }
        val limit = 512 * 1024
        val body = if (request.contentLengthLong > limit) null else request.inputStream.readNBytes(limit + 1)
        if (body == null || body.size > limit) {
            response.status = 413
            response.contentType = "application/json;charset=UTF-8"
            json.writeValue(response.outputStream, mapOf("error" to "提交内容过大"))
            return
        }
        val wrapper = object : HttpServletRequestWrapper(request) {
            override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                val stream = ByteArrayInputStream(body)
                override fun read(): Int = stream.read()
                override fun read(bytes: ByteArray, off: Int, len: Int): Int = stream.read(bytes, off, len)
                override fun isFinished(): Boolean = stream.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException("仅支持同步请求") }
            }
            override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        }
        chain.doFilter(wrapper, response)
    }
}
