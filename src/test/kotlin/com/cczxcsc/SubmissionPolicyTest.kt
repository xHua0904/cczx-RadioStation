package com.cczxcsc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.Executors

class SubmissionPolicyTest {
    @Test fun `每个来源独立限流并返回准确的重试时间`() {
        var now = 0L
        val limiter = SubmissionRateLimiter({ now })
        repeat(20) { assertEquals(0, limiter.retryAfter("a", 20)) }
        assertEquals(60, limiter.retryAfter("a", 20))
        assertEquals(0, limiter.retryAfter("b", 20))
        now = 1001
        assertEquals(59, limiter.retryAfter("a", 20))
        now = 59_999
        assertEquals(1, limiter.retryAfter("a", 20))
        now = 60_000
        assertEquals(0, limiter.retryAfter("a", 20))
    }
    @Test fun `并发投稿不能超过上限`() {
        val limiter = SubmissionRateLimiter({ 0L })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..100).map { pool.submit<Int> { limiter.retryAfter("same", 20) } }.map { it.get() }
            assertEquals(20, results.count { it == 0 })
            assertEquals(80, results.count { it == 60 })
        } finally { pool.shutdownNow() }
    }
    @Test fun `来源容量满时不能挤掉已有计数且过期后恢复`() {
        var now = 0L
        val limiter = SubmissionRateLimiter({ now }, 2)
        assertEquals(0, limiter.retryAfter("a", 1)); assertEquals(0, limiter.retryAfter("b", 1))
        assertEquals(60, limiter.retryAfter("c", 1)); assertEquals(60, limiter.retryAfter("a", 1))
        now = 60_000
        assertEquals(0, limiter.retryAfter("c", 1)); assertEquals(0, limiter.retryAfter("a", 1))
    }
    @Test fun `转发头不能由非可信来源伪造且代理链从右侧验证`() {
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.8"; addHeader("X-Forwarded-For", "192.0.2.1") }
        assertEquals("203.0.113.8", SubmissionSource("").address(request))
        request.remoteAddr = "10.0.0.2"
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "192.0.2.1, 203.0.113.8, 10.0.0.3")
        assertEquals("203.0.113.8", SubmissionSource("10.0.0.0/24").address(request))
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "attacker.example")
        assertEquals("10.0.0.2", SubmissionSource("10.0.0.2/32").address(request))
        assertThrows(IllegalArgumentException::class.java) { SubmissionSource("proxy.example") }
        assertThrows(IllegalArgumentException::class.java) { SubmissionSource("10.0.0.1/99") }
    }
    @Test fun `编码与矩阵参数不能绕过限流且后台登记不受影响`() {
        val filter = RequestLimits(jacksonObjectMapper(), SubmissionPolicy(3, ""))
        fun response(method: String, uri: String, servlet: String): MockHttpServletResponse {
            val request = MockHttpServletRequest(method, uri).apply { remoteAddr = "203.0.113.8"; servletPath = servlet }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ -> response.status = 200 }
            return response
        }
        repeat(3) { assertEquals(200, response("POST", "/api/%73ubmissions", "/api/submissions").status) }
        val rejected = response("POST", "/api/submissions;v=1", "/api/submissions;v=1")
        assertEquals(429, rejected.status); assertEquals("60", rejected.getHeader("Retry-After"))
        assertEquals(200, response("POST", "/api/admin/submissions", "/api/admin/submissions").status)
        assertEquals(200, response("GET", "/api/submissions", "/api/submissions").status)
    }
}
